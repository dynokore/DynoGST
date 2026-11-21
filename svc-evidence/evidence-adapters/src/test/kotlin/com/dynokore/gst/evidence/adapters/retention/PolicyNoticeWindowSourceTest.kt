package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.RelaxationAcknowledgement
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PolicyNoticeWindowSourceTest {
    private val tenant = UUID.randomUUID().toString()
    private val on = LocalDate.of(2026, 3, 1)

    private class Policy(
        var option: SelectedOption,
    ) : PolicyReader {
        var key: VariationPointKey? = null
        var scope: PolicyScope? = null

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            this.key = key
            this.scope = scope
            return ResolvedSetting(key, at, listOf(option), PolicySource.CatalogueDefault)
        }
    }

    private class Relaxations(
        var list: List<RelaxationAcknowledgement> = emptyList(),
    ) : RelaxationReader {
        override fun acknowledgementsInForce(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ) = list
    }

    private fun ack(
        principal: String,
        role: String,
        at: String,
    ) = RelaxationAcknowledgement(
        UUID.randomUUID(),
        RelaxationId(UUID.randomUUID()),
        role,
        principal,
        on,
        Instant.parse(at),
    )

    @Test
    fun `INV-AC-26255 the window is the resolved VP-66 value at tenant scope and never a constant`() {
        val policy = Policy(SelectedOption("SHORTER_ACKNOWLEDGED", "17"))
        val source = PolicyNoticeWindowSource(policy, Relaxations(), defaultDays = 61)
        source.window(tenant, on).days shouldBe 17
        policy.option = SelectedOption("LONGER", "133")
        source.window(tenant, on).days shouldBe 133
        policy.key shouldBe PolicyNoticeWindowSource.KEY
        policy.scope?.tenant shouldBe TenantId(UUID.fromString(tenant))
        policy.scope?.entity shouldBe null
    }

    @Test
    fun `INV-AC-26255 the catalogue default gives the configured default days`() {
        val policy = Policy(SelectedOption(PolicyNoticeWindowSource.DEFAULT_OPTION))
        PolicyNoticeWindowSource(policy, Relaxations(), defaultDays = 61).window(tenant, on).days shouldBe 61
        PolicyNoticeWindowSource(policy, Relaxations(), defaultDays = 62).window(tenant, on).days shouldBe 62
    }

    @Test
    fun `INV-AC-26255 a typed value that is not a whole number or an untyped other option fails closed`() {
        val policy = Policy(SelectedOption("LONGER", "\"9\""))
        val source = PolicyNoticeWindowSource(policy, Relaxations(), defaultDays = 61)
        shouldThrow<IllegalStateException> { source.window(tenant, on) }
        policy.option = SelectedOption("LONGER", "9.5")
        shouldThrow<IllegalStateException> { source.window(tenant, on) }
        policy.option = SelectedOption("LONGER")
        shouldThrow<IllegalArgumentException> { source.window(tenant, on) }
    }

    @Test
    fun `INV-AC-26255 no acknowledgement gives no user ID`() {
        val source = PolicyNoticeWindowSource(Policy(SelectedOption("DAYS_90")), Relaxations(), defaultDays = 61)
        source.acknowledgement(tenant, on) shouldBe null
    }

    @Test
    fun `INV-AC-26255 the first recorded acknowledgement gives its principal and never its role`() {
        val relaxations =
            Relaxations(
                listOf(
                    ack("user-ack-late", "tax.approver", "2026-02-02T00:00:00Z"),
                    ack("user-ack-first", "security.officer", "2026-02-01T00:00:00Z"),
                ),
            )
        val source = PolicyNoticeWindowSource(Policy(SelectedOption("DAYS_90")), relaxations, defaultDays = 61)
        source.acknowledgement(tenant, on)?.userId shouldBe "user-ack-first"
    }
}
