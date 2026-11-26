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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The MISSED window part from the event JSON to the test database (INV-AC-27723). */
class MissedWindowPartDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val t1 = UUID.randomUUID()
    private var bound = t1

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, bound)
                return block().also { connection.commit() }
            }
        }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

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
        val period: String,
        val state: String,
        val version: Long,
        val missedAt: OffsetDateTime?,
    )

    private fun json(
        state: String,
        version: Int,
        period: String = "P1",
        entity: String? = "E1",
        extra: String = "",
    ) = buildString {
        append("{")
        if (entity != null) append("\"entityRef\":\"$entity\",")
        append("\"prescribedPeriod\":\"$period\",\"window\":{\"from\":\"2026-05-01\",\"to\":\"2026-05-07\"},")
        append("\"state\":\"$state\",\"stateVersion\":$version$extra}")
    }

    private fun deliver(
        dataJson: String,
        tenant: UUID = t1,
    ): HandleOutcome {
        bound = tenant
        return handler.handle(
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/aggregation-svc",
                type = "com.dynokore.gst.test.Changed.v1",
                subject = "E1",
                time = Instant.parse("2026-05-08T08:00:00Z"),
                tenant = TenantId(tenant),
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
    }

    private fun stored(tenant: UUID = t1): List<Row> {
        bound = tenant
        return runner.inTransaction {
            dsl
                .select(w.PERIOD_REF, w.STATE, w.STATE_VERSION, w.MISSED_AT)
                .from(w)
                .where(w.TENANT_ID.eq(tenant))
                .orderBy(w.PERIOD_REF)
                .fetch()
                .map { Row(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()) }
        }
    }

    private fun outcome(period: String = "P1"): CompletenessState {
        bound = t1
        return StoredCompleteness.derive(completeness.rows(TenantId(t1), "E1", period))
    }

    @Test
    fun `INV-AC-27723 cases 1 to 5 the first missed instant is kept until the part is CLOSED`() {
        deliver(json("MISSED", 3)) shouldBe HandleOutcome.APPLIED
        val first = stored().single()
        first.state shouldBe "MISSED"
        first.version shouldBe 3
        val instant = first.missedAt.shouldNotBeNull()

        deliver(json("MISSED", 3)) shouldBe HandleOutcome.IGNORED
        stored().single() shouldBe first

        deliver(json("MISSED", 2)) shouldBe HandleOutcome.IGNORED
        stored().single() shouldBe first

        deliver(json("MISSED", 4)) shouldBe HandleOutcome.APPLIED
        stored().single() shouldBe Row("P1", "MISSED", 4, instant)

        deliver(json("CLOSED", 5)) shouldBe HandleOutcome.APPLIED
        stored().single() shouldBe Row("P1", "CLOSED", 5, null)
    }

    @Test
    fun `INV-AC-27723 the missed instant is the transaction instant of the write over another state`() {
        deliver(json("OPEN", 1))
        stored().single().missedAt shouldBe null
        val before = databaseNow()
        deliver(json("MISSED", 2))
        val instant = stored().single().missedAt.shouldNotBeNull()
        val after = databaseNow()
        (instant.isAfter(before) && instant.isBefore(after)) shouldBe true
        listOf("OPEN" to 3, "LATE" to 5).forEach { (state, version) ->
            deliver(json(state, version))
            stored().single().missedAt shouldBe null
            deliver(json("MISSED", version + 1))
            stored()
                .single()
                .missedAt
                .shouldNotBeNull()
                .isAfter(instant) shouldBe true
        }
    }

    // each call is its own transaction, so current_timestamp is a later instant each time
    private fun databaseNow(): OffsetDateTime {
        val now = dsl.select(DSL.currentOffsetDateTime()).fetchSingle().value1()
        connection.commit()
        return now.shouldNotBeNull()
    }

    @Test
    fun `INV-AC-27723 case 6 an event without entityRef or with lower case missed writes nothing`() {
        shouldThrow<ContractViolation> { deliver(json("MISSED", 3, entity = null)) }
        shouldThrow<ContractViolation> { deliver(json("missed", 3)) }
        stored().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-27723 case 7 a MISSED part gives the outcome of an OPEN part and LATE still fails`() {
        deliver(json("OPEN", 1))
        val open = outcome()
        open shouldBe CompletenessState.Unavailable
        deliver(json("MISSED", 2))
        outcome() shouldBe open
        deliver(json("LATE", 3))
        outcome() shouldBe CompletenessState.Failed(listOf("window:P1"))
    }

    @Test
    fun `INV-AC-27723 case 8 the row of another tenant is not changed`() {
        deliver(json("MISSED", 3))
        val mine = stored()
        val other = UUID.randomUUID()
        deliver(json("CLOSED", 9), other) shouldBe HandleOutcome.APPLIED
        stored() shouldBe mine
        stored(other).single() shouldBe Row("P1", "CLOSED", 9, null)
    }

    @Test
    fun `INV-AC-27723 case 9 a MISSED part of unknown span is stored under UNKNOWN-SPAN`() {
        deliver(json("MISSED", 1, "UNKNOWN-SPAN", extra = ",\"spanKnown\":false")) shouldBe HandleOutcome.APPLIED
        val row = stored().single()
        row.period shouldBe "UNKNOWN-SPAN"
        row.state shouldBe "MISSED"
        row.missedAt.shouldNotBeNull()
    }

    @Test
    fun `INV-AC-27723 case 10 a wrong use of spanKnown writes nothing`() {
        shouldThrow<ContractViolation> {
            deliver(json("CLOSED", 1, "UNKNOWN-SPAN", extra = ",\"spanKnown\":false"))
        }
        shouldThrow<ContractViolation> { deliver(json("MISSED", 1, "2026Q1", extra = ",\"spanKnown\":false")) }
        shouldThrow<ContractViolation> {
            deliver(json("MISSED", 1, "UNKNOWN-SPAN", extra = ",\"spanKnown\":\"false\""))
        }
        stored().shouldBeEmpty()
    }

    @Test
    fun `INV-AC-27723 case 11 the UNKNOWN-SPAN row changes no outcome of the period P1`() {
        deliver(json("CLOSED", 1))
        val before = outcome()
        before shouldBe CompletenessState.Passed
        deliver(json("MISSED", 1, "UNKNOWN-SPAN", extra = ",\"spanKnown\":false"))
        outcome() shouldBe before
    }
}
