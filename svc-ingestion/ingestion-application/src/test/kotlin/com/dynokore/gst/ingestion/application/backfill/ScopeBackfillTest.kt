package com.dynokore.gst.ingestion.application.backfill

import com.dynokore.gst.ingestion.application.recording.CanonicalRecorder
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.application.recording.RecordTransaction
import com.dynokore.gst.ingestion.domain.backfill.BackfillRefusal
import com.dynokore.gst.ingestion.domain.backfill.DatePeriod
import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ScopeBackfillTest {
    private class FakeSource(
        private val answer: SubmissionScopeRead,
    ) : SubmissionScopeSource {
        val reads = mutableListOf<String>()

        override fun read(entityRef: String): SubmissionScopeRead {
            reads += entityRef
            return answer
        }
    }

    /** The canonical fake behind the real `RecordTransaction`: its calls are what was recorded. */
    private class FakeCanonical : CanonicalRecorder {
        var calls = 0

        override fun record(command: RecordDocumentCommand): CanonicalAnswer {
            calls++
            return CanonicalAnswer.Created(UUID.randomUUID(), 1)
        }
    }

    private val entity = "entity-1"
    private val activation = LocalDate.of(2026, 3, 1)
    private val affected = RequestedPeriod(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20))
    private val current = DatePeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
    private val answered = SubmissionScopeRead.Answer(activation, current)

    private val canonical = FakeCanonical()
    private val recorder = RecordTransaction(canonical)

    /** Applies the result as the intake will: only an accepted or a non-backfill request is recorded. */
    private fun intake(
        source: FakeSource,
        date: LocalDate,
        backfill: BackfillRequest?,
    ): ScopeBackfillResult {
        val result = ScopeBackfill(source).decide(ScopeBackfillCommand(entity, date, backfill))
        val records =
            when (result) {
                ScopeBackfillResult.NotBackfill, ScopeBackfillResult.Accepted -> true
                is ScopeBackfillResult.Refused, is ScopeBackfillResult.Unavailable -> false
            }
        if (records) recorder.execute(RecordDocumentCommand(UUID.randomUUID(), "erp-acme", "K", 1, "{}"))
        return result
    }

    private fun correction(period: RequestedPeriod? = affected) = BackfillRequest("SILENT_FAILURE_CORRECTION", period)

    private val earlyAdoption = BackfillRequest("EARLY_ADOPTION", null)
    private val migration = BackfillRequest("MID_PERIOD_MIGRATION", null)

    private fun assertRefused(
        result: ScopeBackfillResult,
        reason: BackfillRefusal,
    ) {
        val refused = result as ScopeBackfillResult.Refused
        refused.reason shouldBe reason
        refused.code shouldBe "ING-025"
    }

    @Test
    fun `INV-AC-27046 a silent failure correction accepts a document inside the affected period and reads nothing`() {
        val source = FakeSource(answered)
        listOf(affected.start, LocalDate.of(2026, 1, 15), affected.end).forEach {
            intake(source, it, correction()) shouldBe ScopeBackfillResult.Accepted
        }
        source.reads.size shouldBe 0
        canonical.calls shouldBe 3
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-27046 a silent failure correction refuses the day before the affected period with ING-025 and nothing is recorded`() {
        val source = FakeSource(answered)
        val result = intake(source, affected.start.minusDays(1), correction())
        assertRefused(result, BackfillRefusal.OUTSIDE_AFFECTED_PERIOD)
        canonical.calls shouldBe 0
        source.reads.size shouldBe 0
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-27046 a silent failure correction refuses the day after the affected period with ING-025 and nothing is recorded`() {
        val source = FakeSource(answered)
        val result = intake(source, affected.end.plusDays(1), correction())
        assertRefused(result, BackfillRefusal.OUTSIDE_AFFECTED_PERIOD)
        canonical.calls shouldBe 0
        source.reads.size shouldBe 0
    }

    @Test
    fun `INV-AC-27046 a silent failure correction without an affected period is refused with ING-025`() {
        val source = FakeSource(answered)
        val result = intake(source, LocalDate.of(2026, 1, 15), correction(null))
        assertRefused(result, BackfillRefusal.AFFECTED_PERIOD_REQUIRED)
        canonical.calls shouldBe 0
        source.reads.size shouldBe 0
    }

    @Test
    fun `INV-AC-27046 a request without backfill is not a backfill and reads nothing`() {
        val source = FakeSource(SubmissionScopeRead.Unavailable("timeout"))
        intake(source, LocalDate.of(2020, 1, 1), null) shouldBe ScopeBackfillResult.NotBackfill
        source.reads.size shouldBe 0
        canonical.calls shouldBe 1
    }

    @Test
    fun `INV-AC-27046 an early adoption before the activation date is refused and the day itself is accepted`() {
        val source = FakeSource(answered)
        val before = intake(source, activation.minusDays(1), earlyAdoption)
        assertRefused(before, BackfillRefusal.BEFORE_ACTIVATION)
        canonical.calls shouldBe 0
        intake(source, activation, earlyAdoption) shouldBe ScopeBackfillResult.Accepted
        intake(source, activation.plusDays(1), earlyAdoption) shouldBe ScopeBackfillResult.Accepted
        canonical.calls shouldBe 2
        source.reads shouldBe listOf(entity, entity, entity)
    }

    @Test
    fun `INV-AC-27046 a mid period migration accepts the current period only`() {
        val source = FakeSource(answered)
        listOf(current.start.minusDays(1), current.end.plusDays(1)).forEach {
            assertRefused(intake(source, it, migration), BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
        }
        canonical.calls shouldBe 0
        listOf(current.start, current.end).forEach {
            intake(source, it, migration) shouldBe ScopeBackfillResult.Accepted
        }
        canonical.calls shouldBe 2
        source.reads shouldBe List(4) { entity }
        // No current period: no date is in scope.
        val none = FakeSource(SubmissionScopeRead.Answer(activation, null))
        assertRefused(intake(none, current.start, migration), BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
        canonical.calls shouldBe 2
    }

    @Test
    fun `INV-AC-27046 an unanswered read is Unavailable and records nothing`() {
        listOf(earlyAdoption, migration).forEach {
            val source = FakeSource(SubmissionScopeRead.Unavailable("timeout"))
            intake(source, current.start, it) shouldBe ScopeBackfillResult.Unavailable("timeout")
            source.reads shouldBe listOf(entity)
        }
        canonical.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27046 an entity with no activation date refuses an early adoption`() {
        val source = FakeSource(SubmissionScopeRead.Answer(null, current))
        listOf(activation.minusDays(1), activation, current.start).forEach {
            assertRefused(intake(source, it, earlyAdoption), BackfillRefusal.BEFORE_ACTIVATION)
        }
        canonical.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27046 an unknown situation or a reversed period throws, reads nothing and records nothing`() {
        val source = FakeSource(answered)
        listOf("", "early_adoption", "EARLY_ADOPTION ", "EARLY_ADOPTION,MID_PERIOD_MIGRATION", "*").forEach {
            shouldThrow<IllegalArgumentException> { intake(source, activation, BackfillRequest(it, null)) }
        }
        val reversed = RequestedPeriod(affected.end, affected.start)
        listOf("EARLY_ADOPTION", "SILENT_FAILURE_CORRECTION", "MID_PERIOD_MIGRATION").forEach {
            shouldThrow<IllegalArgumentException> { intake(source, activation, BackfillRequest(it, reversed)) }
        }
        source.reads.size shouldBe 0
        canonical.calls shouldBe 0
    }
}
