package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.AggregateTransaction.Companion.AGGREGATE_TRANSACTION
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.IntakeReceipt.Companion.INTAKE_RECEIPT
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.SeenRevision.Companion.SEEN_REVISION
import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPage
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.IntakeListingReader
import com.dynokore.gst.aggregation.application.ListedIntake
import com.dynokore.gst.aggregation.application.ListedIntakeOperation
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record4
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The intake receipts of a principal (INV-AC-28765), read by keyset on the total order `sourceKey`, `recordedAt`,
 * `sourceRevision`, `entityRef`, `intake_operation`, all ascending (API-18); the row comparison of the keyset and the
 * ORDER BY use the same column collations, so no item is skipped or repeated between pages. There is no offset.
 *
 * The two operations store their data in separate tables and can accept the same triple, so the aggregates of an item
 * are read from the tables of its own operation only: for `BATCH` the aggregate stored for the triple and the one a
 * revision seen without one was compared with; for `TRANSACTIONS` the aggregates that hold its dated transactions.
 * `superseded_by` is not followed: a superseding revision brings its own data under its own receipt.
 */
class JooqIntakeListingReader(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : IntakeListingReader {
    override fun entityRefs(filter: IntakeListingFilter): Set<String> =
        runner.inTransaction {
            dsl
                .selectDistinct(INTAKE_RECEIPT.ENTITY_REF)
                .from(INTAKE_RECEIPT)
                .where(matching(tenants.current().value, filter))
                .fetch()
                .map { checkNotNull(it.value1()) }
                .toSet()
        }

    override fun page(
        filter: IntakeListingFilter,
        permittedEntityRefs: Set<String>,
        after: IntakeListingPosition?,
        limit: Int,
    ): IntakeListingPage {
        if (permittedEntityRefs.isEmpty()) return IntakeListingPage(emptyList(), null)
        return runner.inTransaction {
            val tenant = tenants.current().value
            val permitted = matching(tenant, filter).and(INTAKE_RECEIPT.ENTITY_REF.`in`(permittedEntityRefs))
            val rows = rows(permitted, after, limit)
            val items = withAggregateIds(tenant, rows.take(limit))
            IntakeListingPage(items, items.lastOrNull()?.takeIf { rows.size > limit }?.let { position(it) })
        }
    }

    private fun matching(
        tenant: UUID,
        filter: IntakeListingFilter,
    ): Condition =
        INTAKE_RECEIPT.TENANT_ID
            .eq(tenant)
            .and(INTAKE_RECEIPT.SUBMITTED_BY.eq(filter.submittedBy))
            .and(INTAKE_RECEIPT.RECORDED_AT.ge(bound(filter.recordedFrom)))
            .and(INTAKE_RECEIPT.RECORDED_AT.lt(bound(filter.recordedTo)))

    /**
     * `recorded_at` holds whole microseconds and the driver rounds a finer instant to the nearest one, which can move
     * a bound across a stored value. Both bounds are rounded up: for a stored value t, `t >= from` and `t < to` hold
     * exactly when they hold for the bounds rounded up.
     */
    private fun bound(instant: Instant): OffsetDateTime {
        val whole = instant.truncatedTo(ChronoUnit.MICROS)
        return stored(if (whole == instant) whole else whole.plus(1, ChronoUnit.MICROS))
    }

    private fun stored(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private fun position(item: ListedIntake) =
        IntakeListingPosition(
            item.sourceKey,
            item.recordedAt,
            item.sourceRevision,
            item.entityRef,
            item.intakeOperation,
        )

    /**
     * One row more than [limit] is read: its presence is what tells that a next page exists. The text order of
     * `intake_operation` is the order of [ListedIntakeOperation]. The rows carry no aggregate yet.
     */
    private fun rows(
        permitted: Condition,
        after: IntakeListingPosition?,
        limit: Int,
    ): List<ListedIntake> {
        val later =
            after?.let {
                DSL
                    .row(
                        INTAKE_RECEIPT.SOURCE_KEY,
                        INTAKE_RECEIPT.RECORDED_AT,
                        INTAKE_RECEIPT.SOURCE_REVISION,
                        INTAKE_RECEIPT.ENTITY_REF,
                        INTAKE_RECEIPT.INTAKE_OPERATION,
                    ).gt(
                        it.sourceKey,
                        stored(it.recordedAt),
                        it.sourceRevision,
                        it.entityRef,
                        it.intakeOperation.name,
                    )
            } ?: DSL.noCondition()
        return dsl
            .selectFrom(INTAKE_RECEIPT)
            .where(permitted)
            .and(later)
            .orderBy(
                INTAKE_RECEIPT.SOURCE_KEY,
                INTAKE_RECEIPT.RECORDED_AT,
                INTAKE_RECEIPT.SOURCE_REVISION,
                INTAKE_RECEIPT.ENTITY_REF,
                INTAKE_RECEIPT.INTAKE_OPERATION,
            ).limit(limit + 1)
            .fetch()
            .map {
                ListedIntake(
                    intakeOperation = ListedIntakeOperation.valueOf(checkNotNull(it.intakeOperation)),
                    entityRef = checkNotNull(it.entityRef),
                    sourceKey = checkNotNull(it.sourceKey),
                    sourceRevision = checkNotNull(it.sourceRevision),
                    aggregateType = AggregateType.valueOf(checkNotNull(it.aggregateType)),
                    recordedAt = checkNotNull(it.recordedAt).toInstant(),
                    aggregateIds = emptyList(),
                )
            }
    }

    private fun withAggregateIds(
        tenant: UUID,
        items: List<ListedIntake>,
    ): List<ListedIntake> {
        val ids =
            items
                .groupBy({ it.intakeOperation }, { Triple(it.entityRef, it.sourceKey, it.sourceRevision) })
                .mapValues { (operation, triples) ->
                    when (operation) {
                        ListedIntakeOperation.BATCH -> batchIds(tenant, triples)
                        ListedIntakeOperation.TRANSACTIONS -> transactionIds(tenant, triples)
                    }
                }
        return items.map {
            val triple = Triple(it.entityRef, it.sourceKey, it.sourceRevision)
            it.copy(aggregateIds = ids.getValue(it.intakeOperation)[triple].orEmpty())
        }
    }

    /** The aggregate of each triple and that of its `seen_revision` row; the union keeps each id once. */
    private fun batchIds(
        tenant: UUID,
        triples: List<Triple<String, String, Long>>,
    ): Map<Triple<String, String, Long>, List<UUID>> {
        val own =
            DSL
                .select(AGGREGATE.ENTITY_REF, AGGREGATE.SOURCE_KEY, AGGREGATE.SOURCE_REVISION, AGGREGATE.AGGREGATE_ID)
                .from(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenant))
                .and(
                    DSL
                        .row(AGGREGATE.ENTITY_REF, AGGREGATE.SOURCE_KEY, AGGREGATE.SOURCE_REVISION)
                        .`in`(triples.map { DSL.row(it.first, it.second, it.third) }),
                )
        val seen =
            DSL
                .select(
                    SEEN_REVISION.ENTITY_REF,
                    SEEN_REVISION.SOURCE_KEY,
                    SEEN_REVISION.SOURCE_REVISION,
                    SEEN_REVISION.AGGREGATE_ID,
                ).from(SEEN_REVISION)
                .where(SEEN_REVISION.TENANT_ID.eq(tenant))
                .and(
                    DSL
                        .row(SEEN_REVISION.ENTITY_REF, SEEN_REVISION.SOURCE_KEY, SEEN_REVISION.SOURCE_REVISION)
                        .`in`(triples.map { DSL.row(it.first, it.second, it.third) }),
                )
        return byTriple(dsl.fetch(own.union(seen)))
    }

    /** The distinct aggregates that hold dated transactions of each triple; a row in no aggregate yet adds none. */
    private fun transactionIds(
        tenant: UUID,
        triples: List<Triple<String, String, Long>>,
    ): Map<Triple<String, String, Long>, List<UUID>> =
        byTriple(
            dsl
                .selectDistinct(
                    AGGREGATE_TRANSACTION.ENTITY_REF,
                    AGGREGATE_TRANSACTION.SOURCE_KEY,
                    AGGREGATE_TRANSACTION.SOURCE_REVISION,
                    AGGREGATE_TRANSACTION.AGGREGATE_ID,
                ).from(AGGREGATE_TRANSACTION)
                .where(AGGREGATE_TRANSACTION.TENANT_ID.eq(tenant))
                .and(AGGREGATE_TRANSACTION.AGGREGATE_ID.isNotNull)
                .and(
                    DSL
                        .row(
                            AGGREGATE_TRANSACTION.ENTITY_REF,
                            AGGREGATE_TRANSACTION.SOURCE_KEY,
                            AGGREGATE_TRANSACTION.SOURCE_REVISION,
                        ).`in`(triples.map { DSL.row(it.first, it.second, it.third) }),
                ).fetch(),
        )

    private fun byTriple(
        rows: List<Record4<String?, String?, Long?, UUID?>>,
    ): Map<Triple<String, String, Long>, List<UUID>> =
        rows
            .groupBy(
                { Triple(checkNotNull(it.value1()), checkNotNull(it.value2()), checkNotNull(it.value3())) },
                { checkNotNull(it.value4()) },
            ).mapValues { (_, ids) -> ids.sorted() }
}
