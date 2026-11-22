package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.configresolver.ScopeLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A recomputation resolves VP-62 per period, on the period's own filing due date, not on the date it starts from
 * (INV-REQ-82 behavior 4 and 5, INV-AC-27793).
 */
internal class NonBusinessDayPerPeriodRuleTest : Vp62TestSupport() {
    /** One fixed clock for every case: no case reads the wall clock. */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC)

    private val settled =
        mapOf("2026-06-01" to "2026-08-01", "2026-09-01" to "2026-11-02", "2026-11-01" to "2026-12-07")
    private val reverted =
        mapOf("2026-06-01" to "2026-08-01", "2026-09-01" to "2026-11-02", "2026-11-01" to "2026-12-05")

    @Test
    fun `INV-AC-27793 a recomputation from before the setting changes nothing`() {
        run(fixedClock) { s ->
            s.loosen()
            s.assertConverged()
            assertThat(s.dueDates().filterKeys { it in settled.keys }).isEqualTo(settled)
        }
    }

    @Test
    fun `INV-AC-27793 a later setting does not revert a period moved under an earlier one`() {
        run(fixedClock) { s ->
            s.loosen()
            s.set(s.scope(), ScopeLevel.ENTITY, null, day("2026-11-01"), keep)
            assertThat(s.deliver(s.policyChanges().last().dataJson)).isEqualTo("APPLIED")
            assertThat(s.dueDates().filterKeys { it in settled.keys }).isEqualTo(reverted)
            assertThat(s.ruleEvents("2026-09-01")).hasSize(1)
            val november = s.ruleEvents("2026-11-01")
            assertThat(november).hasSize(2)
            assertThat(november.last()["oldFilingDueDate"].asString()).isEqualTo("2026-12-07")
            assertThat(november.last()["newFilingDueDate"].asString()).isEqualTo("2026-12-05")
            s.assertConverged()
        }
    }

    @Test
    fun `INV-AC-27793 the rule of a period is read on its filing due date and not on its start`() {
        run(fixedClock) { s ->
            s.loosen()
            s.set(s.scope(), ScopeLevel.ENTITY, null, day("2026-12-01"), keep)
            assertThat(s.deliver(s.policyChanges().last().dataJson)).isEqualTo("APPLIED")
            assertThat(s.dueDates().filterKeys { it in settled.keys }).isEqualTo(reverted)
            s.assertConverged()
        }
    }
}
