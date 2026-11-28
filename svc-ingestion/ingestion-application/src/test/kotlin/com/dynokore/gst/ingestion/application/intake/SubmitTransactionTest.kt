package com.dynokore.gst.ingestion.application.intake

import com.dynokore.gst.ingestion.application.backfill.BackfillRequest
import com.dynokore.gst.ingestion.application.backfill.ScopeBackfill
import com.dynokore.gst.ingestion.application.backfill.ScopeBackfillResult
import com.dynokore.gst.ingestion.application.backfill.SubmissionScopeRead
import com.dynokore.gst.ingestion.application.backfill.SubmissionScopeSource
import com.dynokore.gst.ingestion.application.recording.CanonicalRecorder
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.application.recording.RecordTransaction
import com.dynokore.gst.ingestion.domain.backfill.BackfillRefusal
import com.dynokore.gst.ingestion.domain.backfill.DatePeriod
import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.gst.ingestion.domain.recording.RecordedResult
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class SubmitTransactionTest {
    private val sample = RecordedResult(1, "SUBMITTABLE", LocalDate.of(2026, 10, 1), "2026Q3", emptyList())

    /** Both fakes write here, so the order of their calls can be asserted. */
    private val order = mutableListOf<String>()

    private class FakeSource(
        private val answer: SubmissionScopeRead,
        private val order: MutableList<String>,
    ) : SubmissionScopeSource {
        var calls = 0

        override fun read(entityRef: String): SubmissionScopeRead {
            calls++
            order += "scope $entityRef"
            return answer
        }
    }

    private class FakeCanonical(
        private val answer: CanonicalAnswer,
        private val order: MutableList<String>,
    ) : CanonicalRecorder {
        var calls = 0
        val recorded = mutableListOf<RecordDocumentCommand>()

        override fun record(command: RecordDocumentCommand): CanonicalAnswer {
            calls++
            recorded += command
            order += "record"
            return answer
        }
    }

    private val activation = LocalDate.of(2026, 3, 1)
    private val answered =
        SubmissionScopeRead.Answer(activation, DatePeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
    private val docUuid = UUID.randomUUID()
    private val document = RecordDocumentCommand(UUID.randomUUID(), "erp-acme", "K", 1, "{}")
    private val earlyAdoption = BackfillRequest("EARLY_ADOPTION", null)

    private fun source(answer: SubmissionScopeRead = answered) = FakeSource(answer, order)

    private fun canonical(answer: CanonicalAnswer = CanonicalAnswer.Created(docUuid, 1, sample)) =
        FakeCanonical(answer, order)

    private fun useCase(
        source: FakeSource,
        canonical: FakeCanonical,
    ) = SubmitTransaction(ScopeBackfill(source), RecordTransaction(canonical))

    private fun command(
        date: LocalDate,
        backfill: BackfillRequest?,
    ) = SubmitTransactionCommand("entity-1", date, backfill, document)

    @Test
    fun `INV-AC-27059 a request without backfill is recorded once and reads no scope`() {
        val source = source(SubmissionScopeRead.Unavailable("timeout"))
        val canonical = canonical()
        val result = useCase(source, canonical).execute(command(LocalDate.of(2020, 1, 1), null))
        result shouldBe SubmitTransactionResult.Recorded(RecordingOutcome.New(docUuid, sample))
        canonical.calls shouldBe 1
        canonical.recorded shouldBe listOf(document)
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27059 an accepted backfill is recorded once`() {
        val source = source()
        val canonical = canonical()
        val result = useCase(source, canonical).execute(command(activation, earlyAdoption))
        result shouldBe SubmitTransactionResult.Recorded(RecordingOutcome.New(docUuid, sample))
        canonical.calls shouldBe 1
        canonical.recorded shouldBe listOf(document)
        source.calls shouldBe 1
    }

    @Test
    fun `INV-AC-27059 a refused backfill is not recorded and carries ING-025`() {
        val canonical = canonical()
        val result = useCase(source(), canonical).execute(command(activation.minusDays(1), earlyAdoption))
        result shouldBe SubmitTransactionResult.OutOfScope(BackfillRefusal.BEFORE_ACTIVATION, "ING-025")
        canonical.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27059 an unanswered scope read is not recorded and is scope-unavailable`() {
        val source = source(SubmissionScopeRead.Unavailable("timeout"))
        val canonical = canonical()
        val result = useCase(source, canonical).execute(command(activation, earlyAdoption))
        result shouldBe SubmitTransactionResult.ScopeUnavailable("timeout")
        canonical.calls shouldBe 0
        source.calls shouldBe 1
    }

    /** `ScopeBackfill` only builds the default code, so the other code is handed to the step `execute` ends in. */
    @Test
    fun `INV-AC-27059 a refused backfill carrying another code comes out with that code unchanged`() {
        val canonical = canonical()
        val refused = ScopeBackfillResult.Refused(BackfillRefusal.OUTSIDE_CURRENT_PERIOD, "ING-999")
        val result = useCase(source(), canonical).settle(refused, document)
        result shouldBe SubmitTransactionResult.OutOfScope(BackfillRefusal.OUTSIDE_CURRENT_PERIOD, "ING-999")
        canonical.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27059 a replay answer of the recorder is carried unchanged in Recorded`() {
        val canonical = canonical(CanonicalAnswer.Replay(docUuid, 1, sample))
        val result = useCase(source(), canonical).execute(command(activation, null))
        result shouldBe SubmitTransactionResult.Recorded(RecordingOutcome.Replay(docUuid, sample))
        canonical.calls shouldBe 1
    }

    @Test
    fun `INV-AC-27059 the recorder is called after the scope decision and never before it`() {
        useCase(source(), canonical()).execute(command(activation, earlyAdoption))
        order shouldBe listOf("scope entity-1", "record")
    }
}
