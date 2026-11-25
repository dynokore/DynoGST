package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpOption
import com.dynokore.gst.evidence.adapters.retention.ResolvedNoticeWindow
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.ValueType
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpClass
import com.dynokore.platform.configresolver.VpStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-26996: the VP-66 notice window, read through the configuration resolver and the catalogue entry. */
class ResolvedNoticeWindowTest {
    private val tenant = UUID.randomUUID().toString()
    private val key = VariationPointKey("VP-66")
    private val clock = Clock.fixed(Instant.parse("2033-03-31T20:00:00Z"), ZoneOffset.UTC)

    private class Reader(
        private val selected: List<SelectedOption>,
    ) : PolicyReader {
        val reads = mutableListOf<Triple<VariationPointKey, String, LocalDate>>()

        override fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            at: LocalDate,
        ): ResolvedSetting {
            reads += Triple(key, scope.toString(), at)
            return ResolvedSetting(key, at, selected, PolicySource.CatalogueDefault)
        }
    }

    private fun spec(
        min: Int?,
        max: Int?,
    ) = TypedValueSpec(
        ValueType.DURATION,
        "days",
        min?.let { Bound(number = BigDecimal(it)) },
        max?.let { Bound(number = BigDecimal(it)) },
    )

    private fun row(
        defaultKey: String = "DAYS_90",
        longer: TypedValueSpec? = spec(91, null),
        shorter: TypedValueSpec? = spec(1, 89),
    ) = VariationPointDefinition(
        key = key,
        vpClass = VpClass.CONTROL,
        modifiers = emptySet(),
        status = VpStatus.AVAILABLE,
        scopeLevels = setOf(ScopeLevel.INSTALLATION),
        setPermission = "vp-66.set",
        options =
            listOf(
                OptionSpec(defaultKey, Strictness.DEFAULT),
                OptionSpec("LONGER", Strictness.STRICTER, longer != null, longer),
                OptionSpec("SHORTER_ACKNOWLEDGED", Strictness.LOOSER, shorter != null, shorter),
            ),
    )

    private fun only(definition: VariationPointDefinition) =
        VariationPointCatalogue { if (it == definition.key) definition else null }

    private fun days(
        option: String,
        value: String? = null,
        catalogue: VariationPointCatalogue = only(row()),
    ): Int = ResolvedNoticeWindow(Reader(listOf(SelectedOption(option, value))), catalogue, clock).days(tenant)

    @Test
    fun `INV-AC-26996 the key VP-66 is read at installation scope on the business date`() {
        val reader = Reader(listOf(SelectedOption("DAYS_90")))
        ResolvedNoticeWindow(reader, only(row()), clock).days(tenant)
        // 2033-03-31T20:00Z is 1 April in Singapore
        reader.reads shouldBe
            listOf(Triple(key, PolicyScope.installation().toString(), LocalDate.of(2033, 4, 1)))
    }

    @Test
    fun `INV-AC-26996 the default days come from the key of the catalogue default option, not from a literal`() {
        days("DAYS_90") shouldBe 90
        days("DAYS_61", catalogue = only(row(defaultKey = "DAYS_61"))) shouldBe 61
        days("DAYS_62", catalogue = only(row(defaultKey = "DAYS_62"))) shouldBe 62
    }

    @Test
    fun `INV-AC-26996 the real catalogue entry gives its default and holds both typed options`() {
        val generated = VariationPoint.entries.single { it.id == "VP-66" }
        val definition = definitionOf(generated)
        val default = definition.options.single { it.strictness == Strictness.DEFAULT }.key
        val real = only(definition)
        days(default, catalogue = real) shouldBe default.removePrefix("DAYS_").toInt()
        days("LONGER", "120", real) shouldBe 120
        days("SHORTER_ACKNOWLEDGED", "60", real) shouldBe 60
        days("SHORTER_ACKNOWLEDGED", "1", real) shouldBe 1
        days("SHORTER_ACKNOWLEDGED", "89", real) shouldBe 89
        shouldThrow<IllegalStateException> { days("SHORTER_ACKNOWLEDGED", "90", real) }
        shouldThrow<IllegalStateException> { days("SHORTER_ACKNOWLEDGED", "0", real) }
    }

    @Test
    fun `INV-AC-26996 LONGER and SHORTER_ACKNOWLEDGED give the days of their typed value`() {
        days("LONGER", "91") shouldBe 91
        days("LONGER", "400") shouldBe 400
        days("SHORTER_ACKNOWLEDGED", "60") shouldBe 60
    }

    @Test
    fun `INV-AC-26996 a typed value outside its range throws`() {
        listOf("0", "90", "-1").forEach {
            shouldThrow<IllegalStateException> { days("SHORTER_ACKNOWLEDGED", it) }
        }
        listOf("0", "-5", "90").forEach { shouldThrow<IllegalStateException> { days("LONGER", it) } }
        // even a catalogue that allowed it never gives a window below one day
        shouldThrow<IllegalStateException> { days("LONGER", "0", only(row(longer = spec(0, null)))) }
    }

    @Test
    fun `INV-AC-26996 a value that is missing or not a whole number throws`() {
        listOf(null, "\"60\"", "60.5", "60.0", "true", "{\"days\": 60}", "4294967356").forEach {
            shouldThrow<IllegalStateException> { days("SHORTER_ACKNOWLEDGED", it) }
        }
    }

    @Test
    fun `INV-AC-26996 a typed option without its bounds throws`() {
        shouldThrow<IllegalStateException> { days("LONGER", "120", only(row(longer = null))) }
        shouldThrow<IllegalStateException> { days("LONGER", "120", only(row(longer = spec(null, null)))) }
        shouldThrow<IllegalStateException> { days("SHORTER_ACKNOWLEDGED", "60", only(row(shorter = spec(1, null)))) }
    }

    @Test
    fun `INV-AC-26996 an unknown option key throws, look-alikes included`() {
        listOf("DAYS_60", "DAYS_090", "LONGER_2", "SHORTER", "SHORTER_ACKNOWLEDGED_X", "OTHER").forEach {
            shouldThrow<IllegalStateException> { days(it, "60") }
        }
    }

    @Test
    fun `INV-AC-26996 a default option that is not DAYS_n throws`() {
        listOf("WINDOW_90", "DAYS_", "DAYS_0", "DAYS_9X").forEach {
            shouldThrow<IllegalStateException> { days(it, catalogue = only(row(defaultKey = it))) }
        }
    }

    @Test
    fun `INV-AC-26996 a missing catalogue entry, two selected options and a failed read throw`() {
        shouldThrow<UnknownVariationPoint> { days("DAYS_90", catalogue = VariationPointCatalogue { null }) }
        shouldThrow<IllegalArgumentException> {
            val two = Reader(listOf(SelectedOption("DAYS_90"), SelectedOption("LONGER", "120")))
            ResolvedNoticeWindow(two, only(row()), clock).days(tenant)
        }
        val failing =
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ): ResolvedSetting = throw java.sql.SQLException("down")
            }
        shouldThrow<java.sql.SQLException> { ResolvedNoticeWindow(failing, only(row()), clock).days(tenant) }
    }

    private fun definitionOf(generated: VariationPoint) =
        VariationPointDefinition(
            key = key,
            vpClass = VpClass.valueOf(generated.vpClass.name),
            modifiers = emptySet(),
            status = VpStatus.valueOf(generated.status.name),
            scopeLevels = generated.scopeLevels.orEmpty().mapTo(linkedSetOf()) { ScopeLevel.valueOf(it.name) },
            setPermission = generated.setPermission,
            options = generated.optionSpecs!!.map(::optionOf),
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
                    it.min?.let { bound -> Bound(number = bound.number) },
                    it.max?.let { bound -> Bound(number = bound.number) },
                )
            },
        )
    }
}
