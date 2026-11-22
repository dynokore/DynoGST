package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A memory fake that records each snapshot and answers [answer]. */
internal class RecordingMemory(
    private val answer: Boolean,
) : ReadinessSignalMemory {
    val recorded = mutableListOf<Triple<String, String, ReadinessSnapshot>>()

    override fun record(
        entityRef: String,
        periodRef: String,
        snapshot: ReadinessSnapshot,
    ): Boolean {
        recorded += Triple(entityRef, periodRef, snapshot)
        return answer
    }
}

/** A signals fake that records each fact set. */
internal class RecordingSignals : ReadinessSignals {
    val staged = mutableListOf<ReadinessChangedFacts>()

    override fun stage(facts: ReadinessChangedFacts) {
        staged += facts
    }
}

/** `RunReadiness` stages `ReadinessChanged` through its two ports over fakes (INV-AC-26605). */
class RunReadinessSignalTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-15")

    private class Recorder : ReadinessCheckRecorder {
        val appended = mutableListOf<StoredReadinessCheck>()

        override fun append(
            entityRef: String,
            periodRef: String,
            verdict: CheckVerdict,
            checkedAt: Instant,
            approver: String,
            outcomeValues: String?,
        ): StoredReadinessCheck =
            StoredReadinessCheck(
                appended.size + 1L,
                StoredReadinessCheck.checkIdOf(appended.size + 1L),
                entityRef,
                periodRef,
                verdict,
                checkedAt,
                approver,
                outcomeValues,
            ).also { appended += it }
    }

    private fun entry(
        id: String,
        state: LifecycleState,
        overridden: Boolean = false,
    ) = ReadinessEntry(id, listOf(state), day, overridden)

    private val mixed =
        listOf(
            entry("D1", LifecycleState.ACKNOWLEDGED),
            entry("D2", LifecycleState.QUEUED),
            entry("D3", LifecycleState.BLOCKED),
        )

    private fun useCase(
        recorder: Recorder,
        memory: ReadinessSignalMemory,
        signals: ReadinessSignals,
        entries: List<ReadinessEntry>?,
        completeness: CompletenessState = CompletenessState.Passed,
    ) = RunReadiness(
        { _, _ -> entries },
        { _, _ -> ReadinessInputs(day, completeness) },
        recorder,
        clock,
        memory = memory,
        signals = signals,
    )

    @Test
    fun `INV-AC-26605 a NOT_READY run records and stages one fact set with the check id`() {
        val recorder = Recorder()
        val memory = RecordingMemory(true)
        val signals = RecordingSignals()
        useCase(recorder, memory, signals, mixed).run("E", "P", "U")

        val expected =
            ReadinessSnapshot(
                CheckVerdict.NOT_READY,
                2,
                mapOf(LifecycleState.QUEUED to 1, LifecycleState.ACKNOWLEDGED to 1, LifecycleState.BLOCKED to 1),
            )
        memory.recorded shouldBe listOf(Triple("E", "P", expected))
        signals.staged shouldBe listOf(ReadinessChangedFacts("E", "P", expected, recorder.appended.single().checkId))
    }

    @Test
    fun `INV-AC-26605 a NOT_READY run with an override carries the unsubmitted count and the states not the buckets`() {
        val memory = RecordingMemory(true)
        val signals = RecordingSignals()
        val entries =
            listOf(
                entry("D1", LifecycleState.QUEUED),
                entry("D2", LifecycleState.QUEUED, overridden = true),
                entry("D3", LifecycleState.BLOCKED),
                entry("D4", LifecycleState.ACKNOWLEDGED),
                entry("D5", LifecycleState.SUPERSEDED),
            )
        useCase(Recorder(), memory, signals, entries).run("E", "P", "U")

        // outstanding is 2 (the override is removed); unsubmitted is 3
        signals.staged.single().snapshot shouldBe
            ReadinessSnapshot(
                CheckVerdict.NOT_READY,
                3,
                mapOf(
                    LifecycleState.ACKNOWLEDGED to 1,
                    LifecycleState.SUPERSEDED to 1,
                    LifecycleState.QUEUED to 2,
                    LifecycleState.BLOCKED to 1,
                ),
            )
    }

    @Test
    fun `INV-AC-26605 an unchanged snapshot stages nothing while the check is stored`() {
        val recorder = Recorder()
        val memory = RecordingMemory(false)
        val signals = RecordingSignals()
        useCase(recorder, memory, signals, mixed).run("E", "P", "U").shouldBeInstanceOf<ReadinessRunResult.Determined>()

        memory.recorded.size shouldBe 1
        signals.staged shouldBe emptyList()
        recorder.appended.size shouldBe 1
    }

    @Test
    fun `INV-AC-26605 an override-only period is READY and blockedCount is the unsubmitted count 1`() {
        val recorder = Recorder()
        val memory = RecordingMemory(true)
        val signals = RecordingSignals()
        val run =
            useCase(recorder, memory, signals, listOf(entry("D1", LifecycleState.QUEUED, overridden = true)))
                .run("E", "P", "U")

        run.shouldBeInstanceOf<ReadinessRunResult.Determined>().check.verdict shouldBe CheckVerdict.READY
        val snapshot = signals.staged.single().snapshot
        snapshot.verdict shouldBe CheckVerdict.READY
        snapshot.blockedCount shouldBe 1
    }

    @Test
    fun `INV-AC-26605 an unreadable register and an unavailable completeness call neither port and store no check`() {
        val recorder = Recorder()
        val memory = RecordingMemory(true)
        val signals = RecordingSignals()

        useCase(recorder, memory, signals, null).run("E", "P", "U") shouldBe ReadinessRunResult.Undetermined
        useCase(recorder, memory, signals, mixed, CompletenessState.Unavailable).run("E", "P", "U") shouldBe
            ReadinessRunResult.Undetermined

        memory.recorded shouldBe emptyList()
        signals.staged shouldBe emptyList()
        recorder.appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26605 a memory that throws propagates and stages nothing`() {
        val signals = RecordingSignals()
        val memory =
            object : ReadinessSignalMemory {
                override fun record(
                    entityRef: String,
                    periodRef: String,
                    snapshot: ReadinessSnapshot,
                ): Boolean = error("memory down")
            }
        shouldThrow<IllegalStateException> { useCase(Recorder(), memory, signals, mixed).run("E", "P", "U") }
        signals.staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26605 a signals port that throws propagates the exception`() {
        val memory = RecordingMemory(true)
        val signals = ReadinessSignals { error("outbox down") }
        shouldThrow<IllegalStateException> { useCase(Recorder(), memory, signals, mixed).run("E", "P", "U") }
        memory.recorded.size shouldBe 1
    }
}
