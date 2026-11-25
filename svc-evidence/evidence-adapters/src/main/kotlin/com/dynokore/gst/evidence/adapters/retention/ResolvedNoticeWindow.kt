package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.gst.evidence.application.retention.NoticeWindowSetting
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * [NoticeWindowSetting] over the config resolver: VP-66 at installation scope on today's business date
 * (INV-REQ-2462, INV-AC-26996). The catalogue default option `DAYS_<n>` gives `n`, read from the catalogue entry and
 * never written here. `LONGER` and `SHORTER_ACKNOWLEDGED` give the whole number of their typed value, inside the
 * bounds the catalogue entry states for that option; a shortened window with no upper bound is refused. Everything
 * else throws, as does a failed read (fail closed). The window is one value per installation, so the tenant does not
 * select it.
 */
class ResolvedNoticeWindow(
    private val reader: PolicyReader,
    private val catalogue: VariationPointCatalogue,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : NoticeWindowSetting {
    override fun days(tenant: String): Int {
        val definition = catalogue.require(KEY)
        val selected =
            reader.resolve(KEY, PolicyScope.installation(), BusinessDate.today(clock)).selected.single()
        val default = definition.options.single { it.strictness == Strictness.DEFAULT }.key
        val days =
            when (selected.key) {
                LONGER -> typedDays(definition, selected, upperBoundRequired = false)
                SHORTER -> typedDays(definition, selected, upperBoundRequired = true)
                default -> defaultDays(default)
                else -> error("the VP-66 option ${selected.key} is not one this service reads")
            }
        check(days >= 1) { "the VP-66 window is below one day" }
        return days
    }

    private fun defaultDays(key: String): Int {
        val digits = checkNotNull(DEFAULT_KEY.matchEntire(key)) { "the default option of $KEY is '$key', not DAYS_<n>" }
        return digits.groupValues[1].toInt()
    }

    private fun typedDays(
        definition: VariationPointDefinition,
        selected: SelectedOption,
        upperBoundRequired: Boolean,
    ): Int {
        val bounds = checkNotNull(definition.option(selected.key)?.typedValue) { "${selected.key} has no bounds" }
        val min = checkNotNull(bounds.min?.number) { "${selected.key} has no lower bound" }
        val max = bounds.max?.number
        check(max != null || !upperBoundRequired) { "${selected.key} has no upper bound" }
        val field = mapper.readTree(checkNotNull(selected.valueJson) { "${selected.key} has no value" })
        check(field.isIntegralNumber && field.canConvertToInt()) { "the VP-66 value is not a whole number of days" }
        val days = field.intValue()
        val value = days.toBigDecimal()
        check(value >= min && (max == null || value <= max)) { "the ${selected.key} value $days is out of range" }
        return days
    }

    private companion object {
        val KEY = VariationPointKey("VP-66")
        val DEFAULT_KEY = Regex("^DAYS_([1-9][0-9]{0,8})$")
        const val LONGER = "LONGER"
        const val SHORTER = "SHORTER_ACKNOWLEDGED"
    }
}
