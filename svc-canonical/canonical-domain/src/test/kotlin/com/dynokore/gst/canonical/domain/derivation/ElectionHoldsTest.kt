package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.dating.Direction.PURCHASE
import com.dynokore.gst.canonical.domain.dating.Direction.SALE
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.DOCUMENT_SCOPE_VERDICT
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.SUBTOTALS
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Complete
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.HeldBy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ElectionHoldsTest {
    private val per = mapOf(DOCUMENT_SCOPE_VERDICT to "PER-012")
    private val pay = mapOf(SUBTOTALS to "PAY-016")

    @Test
    fun `INV-AC-26602 H1 a purchase without apportionment election holds with PER-012`() {
        ElectionHolds.decide(PURCHASE, null, apportionmentElected = false, rateSourceElected = true) shouldBe per
    }

    @Test
    fun `INV-AC-26602 H2 a purchase with apportionment election has no hold`() {
        ElectionHolds.decide(PURCHASE, null, apportionmentElected = true, rateSourceElected = false) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-26602 H3 a sale never gets PER-012`() {
        ElectionHolds.decide(SALE, null, apportionmentElected = false, rateSourceElected = false) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-26602 H4 USD without rate source election holds with PAY-016`() {
        ElectionHolds.decide(SALE, "USD", apportionmentElected = false, rateSourceElected = false) shouldBe pay
    }

    @Test
    fun `INV-AC-26602 H5 USD with rate source election has no hold`() {
        ElectionHolds.decide(SALE, "USD", apportionmentElected = false, rateSourceElected = true) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-26602 H6 SGD or null currency has no hold whatever the rate source election`() {
        for (elected in listOf(true, false)) {
            ElectionHolds.decide(SALE, "SGD", false, rateSourceElected = elected) shouldBe emptyMap()
            ElectionHolds.decide(SALE, null, false, rateSourceElected = elected) shouldBe emptyMap()
        }
    }

    @Test
    fun `INV-AC-26602 H7 lower case sgd is foreign by exact equality, blank is not a currency`() {
        ElectionHolds.decide(SALE, "sgd", apportionmentElected = false, rateSourceElected = false) shouldBe pay
        ElectionHolds.decide(SALE, "sgd", apportionmentElected = false, rateSourceElected = true) shouldBe emptyMap()
        ElectionHolds.decide(SALE, "", apportionmentElected = false, rateSourceElected = false) shouldBe emptyMap()
        ElectionHolds.decide(SALE, "  ", apportionmentElected = false, rateSourceElected = false) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-26602 H8 a USD purchase with neither election is held by the gate with both reasons in step order`() {
        val holds = ElectionHolds.decide(PURCHASE, "USD", apportionmentElected = false, rateSourceElected = false)
        holds shouldBe mapOf(DOCUMENT_SCOPE_VERDICT to "PER-012", SUBTOTALS to "PAY-016")
        val outcomes =
            DerivationStep.entries.associateWith<DerivationStep, StepOutcome> { step ->
                holds[step]?.let { HeldBy(it) } ?: Complete
            }
        DerivationGate.decide(outcomes) shouldBe GateDecision.Held(listOf("PER-012", "PAY-016"))
    }

    @Test
    fun `INV-AC-26602 H9 each returned code matches the gate hold code pattern`() {
        val pattern = Regex("^[A-Z]{2,10}-[0-9]{3}$")
        ElectionHolds.APPORTIONMENT_HOLD_CODE shouldBe "PER-012"
        ElectionHolds.RATE_SOURCE_HOLD_CODE shouldBe "PAY-016"
        ElectionHolds.REFERENCE_CURRENCY shouldBe "SGD"
        ElectionHolds.decide(PURCHASE, "USD", false, false).values.all { pattern.matches(it) } shouldBe true
    }
}
