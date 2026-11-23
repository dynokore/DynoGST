package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.configresolver.ScopeLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tenancy half of INV-AC-207 (INV-REQ-82, INV-REQ-88): a VP-62 change recomputes the unfiled period only; the filed
 * period and the earlier period keep their dates and publish nothing.
 */
internal class NonBusinessDayUnfiledOnlyTest : Vp62TestSupport() {
    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-26247 a VP-62 change recomputes the unfiled period only and leaves the filed period and the earlier period alone`() {
        run { s ->
            val before = s.dueDates()
            assertThat(before["2026-06-01"]).isEqualTo("2026-08-01")
            assertThat(before["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(before["2026-11-01"]).isEqualTo("2026-12-05")
            s.filed(s.periodOf("2026-11-01"))
            val scope = s.scope()
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            s.set(scope, ScopeLevel.ENTITY, status.relaxation)
            val changed = s.policyChanges().last()
            assertThat(s.deliver(changed.dataJson)).isEqualTo("APPLIED")
            val after = s.dueDates()
            assertThat(after["2026-09-01"]).isEqualTo("2026-11-02")
            assertThat(after["2026-11-01"]).isEqualTo("2026-12-05")
            assertThat(after["2026-06-01"]).isEqualTo("2026-08-01")
            val event = changedDates()(s).single()
            assertThat(event["period"].asString()).isEqualTo(s.periodOf("2026-09-01"))
            assertThat(event["oldFilingDueDate"].asString()).isEqualTo("2026-10-31")
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-11-02")
            assertThat(s.ruleEvents("2026-11-01")).isEmpty()
            assertThat(s.ruleEvents("2026-06-01")).isEmpty()
            assertThat(s.deliver(changed.dataJson, changed.id.value)).isEqualTo("IGNORED")
            assertThat(changedDates()(s)).hasSize(1)
            // the same payload under a new event identifier publishes no further PeriodDueDateChanged
            s.deliver(changed.dataJson)
            assertThat(changedDates()(s)).hasSize(1)
            s.assertConverged()
        }
    }
}
