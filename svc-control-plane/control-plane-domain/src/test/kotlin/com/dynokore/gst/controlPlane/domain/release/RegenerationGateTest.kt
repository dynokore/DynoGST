package com.dynokore.gst.controlPlane.domain.release

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-AC-2080: a candidate train that changes a historical classification is reported and not releasable. */
class RegenerationGateTest {
    private val before =
        mapOf(DocumentId("A") to "SR", DocumentId("B") to "ZR", DocumentId("C") to "ES33")

    private fun verdictOf(run: GateRun) = ReleaseGates.evaluate(setOf(Gate.REGENERATION), listOf(run), exposureReducing = false)

    @Test
    fun `INV-AC-2080 a changed document is reported and the train is not releasable`() {
        val run = RegenerationObservation(before, before + (DocumentId("B") to "SR")).run()
        run.gate shouldBe Gate.REGENERATION
        run.candidate shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf("B")
        val verdict = verdictOf(run)
        verdict.releasable shouldBe false
        val block = verdict.blocks.single()
        block.gate shouldBe Gate.REGENERATION
        block.reason shouldBe GateBlock.Reason.FAILED
        block.introduced shouldBe setOf("B")
    }

    @Test
    fun `INV-AC-2080 counter-case an unchanged corpus passes and is releasable`() {
        val run = RegenerationObservation(before, before.toMap()).run()
        run.candidate shouldBe GateOutcome.PASSED
        run.findings shouldBe emptySet()
        verdictOf(run).releasable shouldBe true
    }

    @Test
    fun `INV-AC-2080 fail closed an empty corpus fails the gate and is not releasable`() {
        val run = RegenerationObservation(emptyMap(), emptyMap()).run()
        run.candidate shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf(RegenerationCheck.EMPTY_CORPUS)
        verdictOf(run).releasable shouldBe false
    }

    @Test
    fun `INV-AC-2080 fail closed no regeneration run holds the release back`() {
        val verdict = ReleaseGates.evaluate(setOf(Gate.REGENERATION), emptyList(), false)
        verdict.blocks.single().reason shouldBe GateBlock.Reason.NO_RUN
    }

    @Test
    fun `INV-AC-2080 a document present only after the change is reported`() {
        val run = RegenerationObservation(before, before + (DocumentId("D") to "SR")).run()
        run.candidate shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf("D")
    }
}
