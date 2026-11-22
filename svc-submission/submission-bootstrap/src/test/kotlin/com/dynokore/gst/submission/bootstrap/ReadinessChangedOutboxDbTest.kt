package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FilingOverride
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessCheck
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessSignal
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.readiness.ReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.ReadinessInputs
import com.dynokore.gst.submission.application.readiness.ReadinessRunResult
import com.dynokore.gst.submission.application.readiness.ReadinessSignalMemory
import com.dynokore.gst.submission.application.readiness.ReadinessSignals
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** `ReadinessChanged` staged by `RunReadiness` over the real register source, memory and outbox (INV-AC-26605). */
class ReadinessChangedOutboxDbTest {
    private val type = "com.dynokore.gst.submission.ReadinessChanged.v1"
    private val day: LocalDate = LocalDate.of(2026, 6, 20)

    private inner class Fixture(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val a: UUID = UUID.randomUUID()
        val b: UUID = UUID.randomUUID()
        val entity = "E-${UUID.randomUUID()}".take(14)
        val period = "P-${UUID.randomUUID()}".take(14)
        val useCase =
            RunReadiness(
                context.getBean(ReadinessEntrySource::class.java),
                { _, _ -> ReadinessInputs(LocalDate.of(2026, 1, 1), CompletenessState.Passed) },
                JooqReadinessCheckStore(dsl, tenants),
                Clock.systemUTC(),
                memory = context.getBean(ReadinessSignalMemory::class.java),
                signals = context.getBean(ReadinessSignals::class.java),
            )

        fun <T> asTenant(
            tenant: UUID,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), "svc:submission-svc") { runner.inTransaction(block) }

        fun run(
            tenant: UUID,
            periodRef: String = period,
        ): ReadinessRunResult = asTenant(tenant) { useCase.run(entity, periodRef, "U1") }

        fun doc(
            tenant: UUID,
            periodRef: String = period,
            states: List<String> = emptyList(),
            date: LocalDate? = day,
            reason: String? = null,
        ): String {
            val uuid = UUID.randomUUID().toString()
            asTenant(tenant) {
                val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
                dsl
                    .insertInto(e)
                    .set(e.TENANT_ID, tenant)
                    .set(e.DOC_UUID, uuid)
                    .set(e.ENTITY_REF, entity)
                    .set(e.PERIOD_REF, periodRef)
                    .set(e.DUE_DATE, LocalDate.of(2026, 11, 30))
                    .set(e.IN_SCOPE, true)
                    .set(e.KIND, "INVOICE")
                    .set(e.TRANSACTION_DATE, date)
                    .set(e.REASON_CODE, reason)
                    .execute()
                states.forEachIndexed { i, state -> version(tenant, uuid, i + 1, state) }
            }
            return uuid
        }

        fun version(
            tenant: UUID,
            uuid: String,
            number: Int,
            state: String,
        ) {
            val v = Submission.SUBMISSION_
            dsl
                .insertInto(v)
                .set(v.TENANT_ID, tenant)
                .set(v.DOC_UUID, uuid)
                .set(v.VERSION, number)
                .set(v.STATE, state)
                .set(v.ENTITY_REF, entity)
                .set(v.SUBMISSION_TYPE, "TYPE_1B")
                .execute()
        }

        fun events(
            tenant: UUID,
            periodRef: String = period,
        ): List<Map<String, Any?>> =
            asTenant(tenant) {
                dsl
                    .selectFrom(Outbox.OUTBOX)
                    .where(Outbox.OUTBOX.TYPE.eq(type))
                    .and(Outbox.OUTBOX.ENTITY_REF.eq(entity))
                    .orderBy(Outbox.OUTBOX.SEQ)
                    .fetch()
                    .map { payload(checkNotNull(it.data).data()) }
                    .filter { it["period"] == periodRef }
            }

        fun checks(
            tenant: UUID,
            periodRef: String = period,
        ): Int =
            asTenant(tenant) {
                dsl
                    .selectCount()
                    .from(ReadinessCheck.READINESS_CHECK)
                    .where(ReadinessCheck.READINESS_CHECK.ENTITY_REF.eq(entity))
                    .and(ReadinessCheck.READINESS_CHECK.PERIOD_REF.eq(periodRef))
                    .fetchOne(0, Int::class.javaObjectType)!!
            }

        fun memory(
            tenant: UUID,
            periodRef: String = period,
        ): List<String> =
            asTenant(tenant) {
                dsl
                    .select(
                        ReadinessSignal.READINESS_SIGNAL.VERDICT,
                        ReadinessSignal.READINESS_SIGNAL.BLOCKED_COUNT,
                        ReadinessSignal.READINESS_SIGNAL.COUNTS_BY_STATE,
                    ).from(ReadinessSignal.READINESS_SIGNAL)
                    .where(ReadinessSignal.READINESS_SIGNAL.ENTITY_REF.eq(entity))
                    .and(ReadinessSignal.READINESS_SIGNAL.PERIOD_REF.eq(periodRef))
                    .fetch()
                    .map { "${it.value1()}|${it.value2()}|${it.value3()}" }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun payload(json: String): Map<String, Any?> =
        JsonMapper.builder().build().readValue(json, Map::class.java) as Map<String, Any?>

    private fun event(
        period: String,
        entity: String,
        verdict: String,
        blocked: Int,
        counts: Map<String, Int>,
    ): Map<String, Any?> =
        mapOf(
            "entityRef" to entity,
            "period" to period,
            "verdict" to verdict,
            "countsByState" to counts,
            "blockedCount" to blocked,
        )

    private fun run(
        dir: Path,
        block: Fixture.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Fixture(context).block() }
    }

    @Test
    fun `INV-AC-26605 the runs of the criterion stage one event exactly when the verdict or the counts changed`(
        @TempDir dir: Path,
    ) = run(dir) {
        doc(a, states = listOf("ACKNOWLEDGED"))
        val d2 = doc(a, states = listOf("QUEUED"))
        val d3 = doc(a, states = listOf("BLOCKED"))
        val first = event(period, entity, "NOT_READY", 2, mapOf("ACKNOWLEDGED" to 1, "BLOCKED" to 1, "QUEUED" to 1))

        // first run: one check, one event
        run(a).shouldBeInstanceOf<ReadinessRunResult.Determined>()
        checks(a) shouldBe 1
        events(a) shouldBe listOf(first)

        // nothing changed: the check is stored, no event
        run(a).shouldBeInstanceOf<ReadinessRunResult.Determined>()
        checks(a) shouldBe 2
        events(a) shouldBe listOf(first)

        // one more entry became queued
        val d4 = doc(a)
        val third = event(period, entity, "NOT_READY", 3, mapOf("ACKNOWLEDGED" to 1, "BLOCKED" to 1, "QUEUED" to 2))
        run(a)
        events(a) shouldBe listOf(first, third)

        // every entry became acknowledged
        asTenant(a) {
            version(a, d2, 2, "ACKNOWLEDGED")
            version(a, d3, 2, "ACKNOWLEDGED")
            version(a, d4, 1, "ACKNOWLEDGED")
        }
        run(a)
        val fourth = event(period, entity, "READY", 0, mapOf("ACKNOWLEDGED" to 4))
        events(a) shouldBe listOf(first, third, fourth)

        // an in-scope entry without a transaction date: undetermined, no check, no event, memory as it was
        val checksBefore = checks(a)
        val memoryBefore = memory(a)
        doc(a, date = null)
        run(a) shouldBe ReadinessRunResult.Undetermined
        checks(a) shouldBe checksBefore
        events(a) shouldBe listOf(first, third, fourth)
        memory(a) shouldBe memoryBefore
    }

    @Test
    fun `INV-AC-26605 a rolled back run leaves no check no event and no memory row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val rolled = "P-rb-${UUID.randomUUID()}".take(14)
        doc(a, periodRef = rolled, states = listOf("QUEUED"))
        runCatching { asTenant(a) { useCase.run(entity, rolled, "U1").also { error("rolled back") } } }
            .isFailure shouldBe true
        checks(a, rolled) shouldBe 0
        events(a, rolled) shouldBe emptyList()
        memory(a, rolled) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26605 an override-only period is READY and its event carries blockedCount 1`(
        @TempDir dir: Path,
    ) = run(dir) {
        val p = "P-ov-${UUID.randomUUID()}".take(14)
        val held = doc(a, periodRef = p, reason = "MDM-001")
        // the holding check names the held document; a RELEASED override of that check overrides it
        val seq =
            asTenant(a) {
                dsl
                    .insertInto(ReadinessCheck.READINESS_CHECK)
                    .set(ReadinessCheck.READINESS_CHECK.TENANT_ID, a)
                    .set(ReadinessCheck.READINESS_CHECK.ENTITY_REF, entity)
                    .set(ReadinessCheck.READINESS_CHECK.PERIOD_REF, p)
                    .set(ReadinessCheck.READINESS_CHECK.VERDICT, "NOT_READY")
                    .set(ReadinessCheck.READINESS_CHECK.CHECKED_AT, OffsetDateTime.now())
                    .set(ReadinessCheck.READINESS_CHECK.APPROVER, "U1")
                    .set(
                        ReadinessCheck.READINESS_CHECK.OUTCOME_VALUES,
                        JSONB.valueOf("{\"heldDocUuids\":[\"$held\"]}"),
                    ).returning(ReadinessCheck.READINESS_CHECK.SEQ)
                    .fetchOne()!!
                    .seq!!
            }
        asTenant(a) {
            val o = FilingOverride.FILING_OVERRIDE
            dsl
                .insertInto(o)
                .set(o.TENANT_ID, a)
                .set(o.ID, "ov-1")
                .set(o.ENTITY_REF, entity)
                .set(o.PERIOD_REF, p)
                .set(o.CHECK_ID, "chk-$seq")
                .set(o.REASON, "r")
                .set(o.STATUS, "RELEASED")
                .set(o.REQUESTED_BY, "U1")
                .set(o.RELEASED_BY, "U2")
                .execute()
        }
        run(a, p).shouldBeInstanceOf<ReadinessRunResult.Determined>()
        events(a, p) shouldBe listOf(event(p, entity, "READY", 1, mapOf("HELD" to 1)))
    }

    @Test
    fun `INV-AC-26605 the second tenant stages its own first event and reads none of the first tenant's`(
        @TempDir dir: Path,
    ) = run(dir) {
        doc(a, states = listOf("QUEUED"))
        doc(b, states = listOf("ACKNOWLEDGED"))
        run(a)
        run(b)
        events(a) shouldBe listOf(event(period, entity, "NOT_READY", 1, mapOf("QUEUED" to 1)))
        events(b) shouldBe listOf(event(period, entity, "READY", 0, mapOf("ACKNOWLEDGED" to 1)))
        memory(b).size shouldBe 1
    }
}
