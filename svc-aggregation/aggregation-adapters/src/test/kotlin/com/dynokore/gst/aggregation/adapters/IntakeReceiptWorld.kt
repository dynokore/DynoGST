package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqReplacedReceiptStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqSeenRevisions
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.Atomically
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.DatedTransaction
import com.dynokore.gst.aggregation.application.ExcludeReplacedReceipt
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.PlacedTransaction
import com.dynokore.gst.aggregation.application.Placement
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.contract.model.AggregationBatchRequest
import com.dynokore.gst.aggregation.contract.model.AggregationBatchRequestLinesByInternalTaxCodeInner
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsRequest
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsRequestTransactionsInner
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

internal const val ALICE = "PRN-00088"
internal const val BOB = "PRN-00089"
internal const val ENTITY = "E-IR"
internal const val KEY = "SK-IR"
internal val DAY: LocalDate = LocalDate.of(2027, 3, 10)

/**
 * The two intake operations over the real database as the `aggregation_runtime` login: the controller, the use cases
 * and the stores composed as `IntakeConfiguration` and `TransactionIntakeConfiguration` compose them. The runner
 * commits when the outermost call returns and rolls back when it throws; a nested call joins it.
 */
internal class IntakeReceiptWorld(
    private val tenant: UUID,
    val connection: Connection,
    shortCodeEvent: String = ShortCodeUsedOutbox.TYPE,
) {
    /** The principal bound for the next call; null stands for a context that holds no actor. */
    var principal: String? = ALICE

    /** The entity code the structure replica answers; null makes the number unbuildable (ING-022). */
    var entityCode: String? = "E1"

    /** The prescribed periods the intake knows. */
    var spans: PeriodSpans =
        PeriodSpans.Known(listOf(PeriodSpan(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31), "2027-03")))

    private var depth = 0
    val runner =
        object : TransactionRunner {
            override fun <R> inTransaction(block: () -> R): R {
                if (depth ==
                    0
                ) {
                    SignedBindings.bind(
                        connection,
                        tenant,
                        principal?.takeIf { it.isNotBlank() } ?: SignedBindings.ACTOR,
                    )
                }
                depth++
                return try {
                    block().also { if (depth == 1) connection.commit() }
                } catch (failure: Throwable) {
                    if (depth == 1) connection.rollback()
                    throw failure
                } finally {
                    depth--
                }
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = TenantId(tenant)

            override fun currentActor(): String? = principal

            override fun <R> bind(
                tenant: TenantId,
                actor: String,
                block: () -> R,
            ): R = block()
        }
    private val codes =
        object : ShortCodes {
            override fun entityCode(entityRef: String): String? = this@IntakeReceiptWorld.entityCode

            override fun businessUnitCode(businessUnitRef: String): String = "B1"
        }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val writer =
        JdbcOutboxWriter(
            dsl,
            "aggregation",
            "aggregation-svc",
            "aggregation-svc-0",
            EventCatalogue(
                listOf(
                    CatalogueEntry(shortCodeEvent, "gst.aggregation.short-code-used.v1", setOf("aggregation-svc")),
                ),
            ),
            Clock.systemUTC(),
        )
    private val windows = JooqWindowStore(dsl, runner, tenants)
    val aggregates =
        JooqAggregateStore(
            dsl,
            runner,
            tenants,
            JooqUsedShortCodes(dsl, tenants, ShortCodeUsedOutbox(writer, tenants)),
        )
    val transactions = JooqTransactionStore(dsl, runner, tenants)
    val seen = JooqSeenRevisions(dsl, tenants, runner)
    private val intake =
        IntakeAggregate(
            codes,
            aggregates,
            windows,
            { _, _, _ -> Cadence.DAILY },
            { _, _, _ -> spans },
            aggregates,
            seen,
        )
    private val controller =
        TransactionIntakeController(
            intake,
            { _, _, _ -> Cadence.DAILY },
            windows,
            transactions,
            ExcludeReplacedReceipt(
                JooqReplacedReceiptStore(dsl, runner, tenants, aggregates),
                object : Atomically {
                    override fun <T> run(block: () -> T): T = runner.inTransaction { block() }
                },
            ),
            { _, _, _ -> spans },
            {},
            runner,
        )

    /** `intakeAggregationBatch` of one line of [amount]; the HTTP status of the answer. */
    @Suppress("LongParameterList")
    fun batch(
        revision: Int,
        amount: String = "100.00",
        key: String = KEY,
        type: AggregationBatchRequest.AggregateType = AggregationBatchRequest.AggregateType.POS,
        taxable: String? = null,
        periodEnd: LocalDate = DAY,
    ): Int =
        controller
            .intakeAggregationBatch(
                "test-flow-context",
                UUID.randomUUID(),
                AggregationBatchRequest(
                    entityRef = ENTITY,
                    businessUnitRef = "BU-IR",
                    aggregateType = type,
                    periodStart = DAY,
                    periodEnd = periodEnd,
                    underlyingRef = "BATCH-1",
                    linesByInternalTaxCode =
                        listOf(
                            AggregationBatchRequestLinesByInternalTaxCodeInner(
                                "SR-STD",
                                BigDecimal(amount),
                                taxableAmount = taxable?.let(::BigDecimal),
                            ),
                        ),
                    sourceKey = key,
                    sourceRevision = revision,
                ),
            ).statusCode
            .value()

    /** `intakeAggregationTransactions` of one dated transaction per reference; the HTTP status of the answer. */
    fun dated(
        revision: Int,
        vararg refs: String,
        key: String = KEY,
        claimable: String? = null,
    ): Int =
        controller
            .intakeAggregationTransactions(
                UUID.randomUUID(),
                AggregationTransactionsRequest(
                    entityRef = ENTITY,
                    businessUnitRef = "BU-IR",
                    aggregateType = AggregationTransactionsRequest.AggregateType.STI,
                    underlyingRef = "BATCH-2",
                    sourceKey = key,
                    sourceRevision = revision,
                    transactions =
                        refs.map {
                            AggregationTransactionsRequestTransactionsInner(
                                it,
                                DAY,
                                "SR-STD",
                                BigDecimal("10.00"),
                                claimableInputTax = claimable?.let(::BigDecimal),
                            )
                        },
                ),
            ).statusCode
            .value()

    /** One call of the transaction store itself, one placed row per reference. */
    fun storeRows(
        vararg refs: String,
        amount: String = "10.00",
    ) = transactions.scoped("BU-IR", AggregateType.STI).store(
        ENTITY,
        KEY,
        1,
        refs.map {
            PlacedTransaction(
                DatedTransaction(it, DAY, "SR-STD", amount),
                Placement(AggregationWindow(DAY, DAY), WindowState.OPEN),
            )
        },
    )

    /** The closing run of [tick] as the scheduled job composes it: an intake with no window repository. */
    fun closeWindows(tick: LocalDate) =
        runner.inTransaction {
            CloseWindows(windows, { _, _, _ -> spans }, IntakeAggregate(codes, aggregates), {}).execute(tick)
        }
}

internal fun <T> receiptWorld(
    tenant: UUID,
    shortCodeEvent: String = ShortCodeUsedOutbox.TYPE,
    block: (IntakeReceiptWorld) -> T,
): T =
    AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
        connection.autoCommit = false
        block(IntakeReceiptWorld(tenant, connection, shortCodeEvent))
    }

internal fun databaseRows(sql: String): List<String> =
    AggregationDb.superuser().use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
        }
    }

internal fun databaseExecute(sql: String): Int =
    AggregationDb.superuser().use { c -> c.createStatement().use { it.executeUpdate(sql) } }

/** Every receipt of the tenant: operation, entity, source key, revision, type and principal. */
internal fun receiptsOf(tenant: UUID): List<String> =
    databaseRows(
        "select concat_ws('|', intake_operation, entity_ref, source_key, source_revision, aggregate_type, " +
            "submitted_by) from aggregation.intake_receipt where tenant_id = '$tenant' " +
            "order by intake_operation, entity_ref, source_key, source_revision",
    )

internal fun receipt(
    operation: String,
    revision: Int,
    principal: String,
    type: String = if (operation == "BATCH") "POS" else "STI",
) = "$operation|$ENTITY|$KEY|$revision|$type|$principal"

/** Every row an intake store writes, as text: the data, the seen revisions, the used codes, events and receipts. */
internal fun intakeDataOf(tenant: UUID): List<String> =
    listOf(
        "aggregate",
        "aggregate_line",
        "aggregate_transaction",
        "seen_revision",
        "used_short_code",
        "outbox",
        "intake_receipt",
    ).flatMap { table ->
        databaseRows(
            "select '$table ' || row_to_json(t)::text from aggregation.$table t " +
                "where tenant_id = '$tenant' order by 1",
        )
    }

/** A receipt already there for the triple, so the receipt insert of the next store of it conflicts. */
internal fun seedReceipt(
    tenant: UUID,
    operation: String,
    revision: Int,
) = databaseExecute(
    "insert into aggregation.intake_receipt (tenant_id, intake_operation, entity_ref, source_key, " +
        "source_revision, aggregate_type, submitted_by) " +
        "values ('$tenant', '$operation', '$ENTITY', '$KEY', $revision, 'POS', 'PRN-SEEDED')",
)

internal fun transmit(tenant: UUID) =
    databaseExecute(
        "update aggregation.aggregate set transmitted_at = now() " +
            "where tenant_id = '$tenant' and superseded_by is null and transmitted_at is null",
    )
