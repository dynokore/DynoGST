package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpOption
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.Direction
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.PrecedenceResolver
import com.dynokore.platform.configresolver.RelaxationAcknowledgement
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingLookup
import com.dynokore.platform.configresolver.SettingSource
import com.dynokore.platform.configresolver.StoredSetting
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.ValueType
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpClass
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
        val source = PolicyNoticeWindowSource(policy, Relaxations())
        source.window(tenant, on).days shouldBe 17
        policy.option = SelectedOption("LONGER", "133")
        source.window(tenant, on).days shouldBe 133
        policy.key shouldBe PolicyNoticeWindowSource.KEY
        policy.scope?.tenant shouldBe TenantId(UUID.fromString(tenant))
        policy.scope?.entity shouldBe null
    }

    @Test
    fun `INV-AC-26255 the catalogue default day count other than 90 drives the window`() {
        PolicyNoticeWindowSource(resolverOver(fixtureCatalogue(61)), Relaxations()).window(tenant, on).days shouldBe 61
        PolicyNoticeWindowSource(resolverOver(fixtureCatalogue(62)), Relaxations()).window(tenant, on).days shouldBe 62
    }

    @Test
    fun `INV-AC-26255 a default option without a typed value makes the source throw`() {
        val source = PolicyNoticeWindowSource(resolverOver(fixtureCatalogue(null)), Relaxations())
        shouldThrow<IllegalArgumentException> { source.window(tenant, on) }
    }

    @Test
    fun `INV-AC-26255 an explicit longer value is returned as given`() {
        val set =
            StoredSetting(
                UUID.randomUUID(),
                listOf(SelectedOption("LONGER", "130")),
                SettingSource.OVERRIDE,
                null,
                null,
                LocalDate.of(2026, 1, 1),
            )
        val reader =
            resolverOver(fixtureCatalogue(61)) { key, ref, _, source, _ ->
                set.takeIf {
                    key == PolicyNoticeWindowSource.KEY && ref.level == ScopeLevel.INSTALLATION &&
                        source == SettingSource.OVERRIDE
                }
            }
        PolicyNoticeWindowSource(reader, Relaxations()).window(tenant, on).days shouldBe 130
    }

    private fun resolverOver(
        catalogue: VariationPointCatalogue,
        lookup: SettingLookup = SettingLookup { _, _, _, _, _ -> null },
    ) = PrecedenceResolver(catalogue, DeploymentForm.SAAS, lookup)

    /** A VP-66 row whose default option carries [defaultDays] as the `default` of its typed value, or none. */
    private fun fixtureCatalogue(defaultDays: Int?): VariationPointCatalogue {
        fun days(
            min: Int,
            max: Int?,
            default: Int?,
        ) = TypedValueSpec(
            ValueType.DURATION,
            "days",
            Bound(number = BigDecimal(min)),
            max?.let { Bound(number = BigDecimal(it)) },
            direction = Direction.UP_STRICTER,
            default = default?.let { Bound(number = BigDecimal(it)) },
        )

        val default =
            if (defaultDays == null) {
                OptionSpec("DAYS_90", Strictness.DEFAULT)
            } else {
                OptionSpec("DAYS_90", Strictness.DEFAULT, true, days(defaultDays, defaultDays, defaultDays))
            }
        val row =
            VariationPointDefinition(
                key = PolicyNoticeWindowSource.KEY,
                vpClass = VpClass.POLICY,
                modifiers = emptySet(),
                status = VpStatus.AVAILABLE,
                scopeLevels = setOf(ScopeLevel.INSTALLATION),
                setPermission = "vp-66.set",
                options =
                    listOf(
                        default,
                        OptionSpec("LONGER", Strictness.STRICTER, true, days(91, null, null)),
                    ),
            )
        return VariationPointCatalogue { if (it == row.key) row else null }
    }

    @Test
    fun `INV-AC-26255 the real catalogue default day count drives the window`() {
        val row = VariationPoint.entries.single { it.id == "VP-66" }
        val expected =
            row.optionSpecs!!
                .single { it.key == "DAYS_90" }
                .typedValue!!
                .default!!
                .number!!
                .intValueExact()
        val catalogue = VariationPointCatalogue { if (it == PolicyNoticeWindowSource.KEY) definitionOf(row) else null }
        PolicyNoticeWindowSource(resolverOver(catalogue), Relaxations()).window(tenant, on).days shouldBe expected
    }

    private fun definitionOf(row: VariationPoint) =
        VariationPointDefinition(
            key = PolicyNoticeWindowSource.KEY,
            vpClass = VpClass.valueOf(row.vpClass.name),
            modifiers = emptySet(),
            status = VpStatus.valueOf(row.status.name),
            scopeLevels = row.scopeLevels.orEmpty().mapTo(linkedSetOf()) { ScopeLevel.valueOf(it.name) },
            setPermission = row.setPermission,
            options = row.optionSpecs!!.map(::optionOf),
        )

    private fun optionOf(option: VpOption): OptionSpec {
        val typed = option.typedValue
        return OptionSpec(
            option.key,
            Strictness.valueOf(option.strictness.name),
            typed != null,
            typed?.let {
                TypedValueSpec(
                    ValueType.valueOf(it.type.name),
                    it.unit,
                    it.min?.let { b -> Bound(number = b.number) },
                    it.max?.let { b -> Bound(number = b.number) },
                    direction = it.direction?.let { d -> Direction.valueOf(d.name) } ?: Direction.NONE,
                    default = it.default?.let { b -> Bound(number = b.number) },
                )
            },
        )
    }

    @Test
    fun `INV-AC-26255 a typed value that is not a whole number or an untyped other option fails closed`() {
        val policy = Policy(SelectedOption("LONGER", "\"9\""))
        val source = PolicyNoticeWindowSource(policy, Relaxations())
        shouldThrow<IllegalStateException> { source.window(tenant, on) }
        policy.option = SelectedOption("LONGER", "9.5")
        shouldThrow<IllegalStateException> { source.window(tenant, on) }
        policy.option = SelectedOption("LONGER")
        shouldThrow<IllegalArgumentException> { source.window(tenant, on) }
    }

    @Test
    fun `INV-AC-26255 no acknowledgement gives no user ID`() {
        val source = PolicyNoticeWindowSource(Policy(SelectedOption("DAYS_90")), Relaxations())
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
        val source = PolicyNoticeWindowSource(Policy(SelectedOption("DAYS_90")), relaxations)
        source.acknowledgement(tenant, ENTITY, NOTIFIED)?.userId shouldBe "user-ack-first"
    }

    private val first = listOf(ack("user-ack-1", "security.officer", "2026-02-01T10:00:00Z"))

    private fun sole(
        policy: Policy,
        relaxations: Relaxations,
    ) = PolicyNoticeWindowSource(policy, relaxations).acknowledgement(tenant, ENTITY, NOTIFIED)

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
