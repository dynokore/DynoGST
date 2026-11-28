package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAggregateWindowChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqWindowPartStateStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WindowPartState
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedHandler
import com.dynokore.gst.submission.application.completeness.StoredCompleteness
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** One window part row per business unit and category, from the event JSON to the test database (INV-AC-29421). */
class WindowIdentityDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val t1 = UUID.randomUUID()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, t1)
                return block().also { connection.commit() }
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(t1)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val handler =
        AggregateWindowChangedHandler(
            tenants,
            JacksonAggregateWindowChangedDataReader(),
            JooqWindowPartStateStore(dsl, runner),
        )
    private val completeness = JooqCompletenessRows(dsl, runner)
    private val w = WindowPartState.WINDOW_PART_STATE

    @AfterEach
    fun close() = connection.close()

    private data class Row(
        val unit: String,
        val type: String,
        val period: String,
        val from: LocalDate,
        val to: LocalDate,
        val state: String,
        val version: Long,
        val missedAt: OffsetDateTime?,
    )

    /** [identity] is the JSON text of the identity members, each with its leading comma. */
    private fun json(
        identity: String,
        state: String,
        version: Int?,
    ) = buildString {
        append("{\"entityRef\":\"E1\",\"prescribedPeriod\":\"P1\",")
        append("\"window\":{\"from\":\"2026-05-01\",\"to\":\"2026-05-07\"},\"state\":\"$state\"")
        if (version != null) append(",\"stateVersion\":$version")
        append("$identity}")
    }

    private fun members(
        unit: String,
        type: String,
    ) = ",\"businessUnitRef\":\"$unit\",\"aggregateType\":\"$type\""

    private fun deliver(dataJson: String): HandleOutcome =
        handler.handle(
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/aggregation-svc",
                type = "com.dynokore.gst.test.Changed.v1",
                subject = "E1",
                time = Instant.parse("2026-05-08T08:00:00Z"),
                tenant = TenantId(t1),
                entity = EntityRef("E1"),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = dataJson,
            ),
        )

    private fun stored(): List<Row> =
        runner.inTransaction {
            dsl
                .selectFrom(w)
                .where(w.TENANT_ID.eq(t1))
                .fetch()
                .map {
                    Row(
                        it.businessUnitRef!!,
                        it.aggregateType!!,
                        it.periodRef!!,
                        it.partFrom!!,
                        it.partTo!!,
                        it.state!!,
                        it.stateVersion!!,
                        it.missedAt,
                    )
                }
        }

    private fun outcome(): CompletenessState = StoredCompleteness.derive(completeness.rows(TenantId(t1), "E1", "P1"))

    private fun summary() = stored().map { listOf(it.unit, it.type, it.state, it.version) }

    private fun applyXyz() {
        val failed = CompletenessState.Failed(listOf("window:P1"))
        deliver(json(members("BU-01", "POS"), "LATE", 1)) shouldBe HandleOutcome.APPLIED
        outcome() shouldBe failed
        deliver(json(members("BU-02", "POS"), "CLOSED", 2)) shouldBe HandleOutcome.APPLIED
        outcome() shouldBe failed
        deliver(json(members("BU-01", "STI"), "MISSED", 3)) shouldBe HandleOutcome.APPLIED
        outcome() shouldBe failed
    }

    private val xyz =
        arrayOf<List<Any>>(
            listOf("BU-01", "POS", "LATE", 1L),
            listOf("BU-02", "POS", "CLOSED", 2L),
            listOf("BU-01", "STI", "MISSED", 3L),
        )

    @Test
    fun `INV-AC-29421 the rows of X, Y and Z are three rows and completeness is Failed after each`() {
        applyXyz()
        summary().shouldContainExactlyInAnyOrder(*xyz)
    }

    @Test
    fun `INV-AC-29421 a later event of X with a stateVersion not above its own changes nothing`() {
        applyXyz()
        val before = stored()
        listOf<Int?>(1, null).forEach {
            deliver(json(members("BU-01", "POS"), "CLOSED", it)) shouldBe HandleOutcome.IGNORED
        }
        stored().shouldContainExactlyInAnyOrder(*before.toTypedArray())
    }

    @Test
    fun `INV-AC-29421 a wrong identity raises ContractViolation and writes nothing`() {
        applyXyz()
        val before = stored()
        val unit = ",\"businessUnitRef\":"
        val type = ",\"aggregateType\":"
        val wrong =
            listOf(
                "$unit\"BU-01\"",
                "$type\"POS\"",
                "${unit}null",
                "${type}null",
                "${unit}null$type\"POS\"",
                "$unit\"BU-01\"${type}null",
                "${unit}null${type}null",
                "${unit}7$type\"POS\"",
                "$unit[\"BU-01\"]$type\"POS\"",
                "$unit\"BU-01\"${type}7",
                "$unit\"BU-01\"$type[\"POS\"]",
                members("", "POS"),
                members("   ", "POS"),
                members("\\t\\u3000", "POS"),
                members("B".repeat(41), "POS"),
            ) +
                listOf("", "pos", "Pos", "sti", "pcp", "POS ", " POS", "POS2", "POS,STI", "POS|STI", "*", "B2B")
                    .map { members("BU-01", it) }
        wrong.forEach { identity ->
            withClue(identity) {
                shouldThrow<ContractViolation> { deliver(json(identity, "CLOSED", 9)) }
            }
        }
        stored().shouldContainExactlyInAnyOrder(*before.toTypedArray())
    }

    @Test
    fun `INV-AC-29421 a businessUnitRef of 40 characters is accepted for each of POS, STI and PCP`() {
        // a character outside the basic plane is one character, as in the contract schema and the database
        val units = listOf("B".repeat(40), "\uD835\uDC01".repeat(40))
        units.forEach { unit ->
            listOf("POS", "STI", "PCP").forEach { type ->
                deliver(json(members(unit, type), "CLOSED", 1)) shouldBe HandleOutcome.APPLIED
            }
        }
        stored().size shouldBe 6
    }

    @Test
    fun `INV-AC-29421 an event with neither member writes a row of its own and changes none of X, Y and Z`() {
        applyXyz()
        val before = stored()
        deliver(json("", "CLOSED", 9)) shouldBe HandleOutcome.APPLIED
        val after = stored()
        after.size shouldBe 4
        after.filter { it.unit.isNotEmpty() }.shouldContainExactlyInAnyOrder(*before.toTypedArray())
        summary().shouldContainExactlyInAnyOrder(*xyz, listOf("", "", "CLOSED", 9L))
        outcome() shouldBe CompletenessState.Failed(listOf("window:P1"))
    }
}
