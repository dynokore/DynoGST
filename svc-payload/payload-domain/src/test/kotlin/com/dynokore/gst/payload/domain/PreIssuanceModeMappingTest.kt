package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreIssuanceModeMappingTest {
    private val pay011 = PreIssuanceReason("PAY-011", "supplierGstn", true)
    private val block = PreIssuanceOutcome.Decided(PreIssuanceVerdict.BLOCK, listOf(pay011), emptySet())
    private val warnFlag =
        PreIssuanceOutcome.Decided(PreIssuanceVerdict.WARN, emptyList(), setOf(PreIssuanceFlag.REGISTER_UNAVAILABLE))
    private val pass = PreIssuanceOutcome.Decided(PreIssuanceVerdict.PASS, emptyList(), emptySet())

    @Test
    fun `INV-AC-26630 M1 advisory turns BLOCK into WARN keeping reasons`() {
        PreIssuanceModeMapping.apply(PreIssuanceMode.ADVISORY, block) shouldBe
            PreIssuanceOutcome.Decided(PreIssuanceVerdict.WARN, listOf(pay011), emptySet())
    }

    @Test
    fun `INV-AC-26630 M2 advisory keeps WARN and PASS`() {
        PreIssuanceModeMapping.apply(PreIssuanceMode.ADVISORY, warnFlag) shouldBe warnFlag
        PreIssuanceModeMapping.apply(PreIssuanceMode.ADVISORY, pass) shouldBe pass
    }

    @Test
    fun `INV-AC-26630 M3 blocking in ERP keeps the outcome`() {
        PreIssuanceModeMapping.apply(PreIssuanceMode.BLOCKING_IN_ERP, block) shouldBe block
    }

    @Test
    fun `INV-AC-26630 M4 off returns no finding`() {
        PreIssuanceModeMapping.apply(PreIssuanceMode.OFF, block) shouldBe pass
        PreIssuanceModeMapping.apply(PreIssuanceMode.OFF, warnFlag) shouldBe pass
    }

    @Test
    fun `INV-AC-26630 M5 refused is unchanged for every mode`() {
        val refused = PreIssuanceOutcome.Refused(PreIssuanceInputError.MISSING_AMOUNT)
        PreIssuanceMode.entries.forEach { PreIssuanceModeMapping.apply(it, refused) shouldBe refused }
    }

    @Test
    fun `INV-AC-26630 M6 end to end wrongful charge by mode`() {
        val decided =
            PreIssuanceVerdictDecision.decide(
                PreIssuanceInput(
                    PreIssuanceSubmissionType.TYPE_1A,
                    PreIssuanceRegisterStatus.NOT_REGISTERED,
                    "90.00",
                    WrongfulChargeCheck.SYSTEM_EVERY_PATH,
                    false,
                ),
            )
        PreIssuanceModeMapping.apply(PreIssuanceMode.ADVISORY, decided) shouldBe
            PreIssuanceOutcome.Decided(PreIssuanceVerdict.WARN, listOf(pay011), emptySet())
        PreIssuanceModeMapping.apply(PreIssuanceMode.BLOCKING_IN_ERP, decided) shouldBe
            PreIssuanceOutcome.Decided(PreIssuanceVerdict.BLOCK, listOf(pay011), emptySet())
        PreIssuanceModeMapping.apply(PreIssuanceMode.OFF, decided) shouldBe pass
    }
}
