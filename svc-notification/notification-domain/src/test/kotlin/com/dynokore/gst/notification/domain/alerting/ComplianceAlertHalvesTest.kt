package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDecision.Raise
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ComplianceAlertHalvesTest {
    private fun values(days: Int) = ComplianceValues("ENT-1", "2026-Q2", LocalDate.of(2026, 4, 20), 1, 0, days)

    private val coverage = ComplianceAlertDraft(AlertClass.COVERAGE, "COVERAGE_GAP", 1, "ENT-1", "2026-Q2")

    private fun deadline(trigger: String) = ComplianceAlertDraft(AlertClass.DEADLINE, trigger, 1, "ENT-1", "2026-Q2")

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-26184 the deadline alert is raised at the first-tier threshold read from the policy and not above it`() {
        val ten = listOf(PolicyTier(1, 10))
        ComplianceAlert.decide(values(10), ten) shouldBe Raise(listOf(coverage, deadline("DUE_DATE_THRESHOLD")))
        ComplianceAlert.decide(values(11), ten) shouldBe Raise(listOf(coverage))
        val seven = listOf(PolicyTier(1, 7))
        ComplianceAlert.decide(values(7), seven) shouldBe Raise(listOf(coverage, deadline("DUE_DATE_THRESHOLD")))
        ComplianceAlert.decide(values(8), seven) shouldBe Raise(listOf(coverage))
        ComplianceAlert.decide(values(3), emptyList()) shouldBe Raise(listOf(coverage))
        ComplianceAlert.decide(values(3), listOf(PolicyTier(1, null))) shouldBe Raise(listOf(coverage))
    }

    @Test
    fun `INV-AC-26185 a due date that has passed raises the PAST_DUE deadline alert`() {
        ComplianceAlert.decide(values(-1), listOf(PolicyTier(1, 10))) shouldBe
            Raise(listOf(coverage, deadline("PAST_DUE")))
        ComplianceAlert.decide(values(-1), emptyList()) shouldBe Raise(listOf(coverage, deadline("PAST_DUE")))
        ComplianceAlert.decide(values(0), listOf(PolicyTier(1, 10))) shouldBe
            Raise(listOf(coverage, deadline("DUE_DATE_THRESHOLD")))
    }
}
