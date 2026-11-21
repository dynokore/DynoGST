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
        var vp40: List<SelectedOption> = listOf(SelectedOption("SINGLE_ATTESTATION_SOLE_OPERATOR"))
        var vp40Scope: PolicyScope? = null
        var vp40On: LocalDate? = null
        var boom: Boolean = false

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            if (key.value == "VP-40") {
                check(!boom) { "reader down" }
                vp40Scope = scope
                vp40On = at
                return ResolvedSetting(key, at, vp40, PolicySource.CatalogueDefault)
            }
            this.key = key
            this.scope = scope
            return ResolvedSetting(key, at, listOf(option), PolicySource.CatalogueDefault)
        }
    }

    private class Relaxations(
        var list: List<RelaxationAcknowledgement> = emptyList(),
        var vp40: List<RelaxationAcknowledgement> = emptyList(),
    ) : RelaxationReader {
        override fun acknowledgementsInForce(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ) = if (key.value == "VP-40") vp40.filter { !it.effectiveFrom.isAfter(at) } else list
    }

    private fun ack(
        principal: String,
        role: String,
        at: String,
        from: LocalDate = on,
    ) = RelaxationAcknowledgement(
        UUID.randomUUID(),
        RelaxationId(UUID.randomUUID()),
        role,
        principal,
        from,
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
        source.acknowledgement(tenant, ENTITY, NOTIFIED) shouldBe null
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
        source.acknowledgement(tenant, ENTITY, NOTIFIED)?.userId shouldBe "user-ack-first"
    }

    private val first = listOf(ack("user-ack-1", "security.officer", "2026-02-01T10:00:00Z"))

    private fun sole(
        policy: Policy,
        relaxations: Relaxations,
    ) = PolicyNoticeWindowSource(policy, relaxations, defaultDays = 61).acknowledgement(tenant, ENTITY, NOTIFIED)

    @Test
    fun `INV-AC-26255 VP-40 sole-operator option with an acknowledgement gives true at entity scope`() {
        val policy = Policy(SelectedOption("DAYS_90"))
        val held = listOf(ack("op", "tax.approver", "2026-01-01T00:00:00Z", LocalDate.of(2026, 1, 1)))
        val result = sole(policy, Relaxations(first, vp40 = held))
        result?.soleOperator shouldBe true
        policy.vp40Scope?.entity shouldBe ENTITY
        policy.vp40Scope?.tenant shouldBe TenantId(UUID.fromString(tenant))
    }

    @Test
    fun `INV-AC-26255 VP-40 selecting another option or lacking an acknowledgement gives false`() {
        val other = Policy(SelectedOption("DAYS_90"))
        other.vp40 = listOf(SelectedOption("TWO_ATTESTERS"))
        val held = listOf(ack("op", "tax.approver", "2026-01-01T00:00:00Z", LocalDate.of(2026, 1, 1)))
        sole(other, Relaxations(first, vp40 = held))?.soleOperator shouldBe false
        sole(Policy(SelectedOption("DAYS_90")), Relaxations(first))?.soleOperator shouldBe false
        val both = Policy(SelectedOption("DAYS_90"))
        both.vp40 = listOf(SelectedOption("SINGLE_ATTESTATION_SOLE_OPERATOR"), SelectedOption("TWO_ATTESTERS"))
        sole(both, Relaxations(first, vp40 = held))?.soleOperator shouldBe false
    }

    @Test
    fun `INV-AC-26255 the VP-40 date is the VP-66 acknowledgement date and a next-day election gives false`() {
        val policy = Policy(SelectedOption("DAYS_90"))
        val dayAfter = ack("op", "r", "2026-02-02T00:00:00Z", LocalDate.of(2026, 2, 2))
        sole(policy, Relaxations(first, vp40 = listOf(dayAfter)))?.soleOperator shouldBe false
        policy.vp40On shouldBe LocalDate.of(2026, 2, 1)
    }

    @Test
    fun `INV-AC-26255 a VP-40 reader exception propagates`() {
        val policy = Policy(SelectedOption("DAYS_90"))
        policy.boom = true
        shouldThrow<IllegalStateException> { sole(policy, Relaxations(first)) }
    }

    private companion object {
        const val ENTITY = "entity-1"
        val NOTIFIED: Instant = Instant.parse("2026-03-01T10:00:00Z")
    }
}
