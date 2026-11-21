package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.gst.evidence.application.retention.NoticeAcknowledgement
import com.dynokore.gst.evidence.application.retention.NoticeWindowSource
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/**
 * [NoticeWindowSource] over the config resolver (VP-66, tenant scope). A catalogue-default selection gives
 * [defaultDays], the installation's catalogue default injected as configuration; a typed option gives its whole-number
 * value; anything else fails closed. The acknowledgement is the first recorded one (the earliest `acknowledgedAt`; the
 * recorded order breaks a tie): its `principal` is a user ID, and its `role` is never read (INV-Q-10647).
 */
class PolicyNoticeWindowSource(
    private val policy: PolicyReader,
    private val relaxations: RelaxationReader,
    private val defaultDays: Int,
    private val mapper: ObjectMapper = ObjectMapper(),
) : NoticeWindowSource {
    override fun window(
        tenant: String,
        on: LocalDate,
    ): NoticeWindow {
        val selected = policy.resolve(KEY, scope(tenant), on).selected.single()
        if (selected.key == DEFAULT_OPTION) return NoticeWindow(defaultDays)
        val field =
            mapper.readTree(
                requireNotNull(selected.valueJson) { "the VP-66 option ${selected.key} has no value" },
            )
        check(field.isIntegralNumber && field.canConvertToInt()) { "the VP-66 value is not a whole number of days" }
        return NoticeWindow(field.intValue())
    }

    override fun acknowledgement(
        tenant: String,
        on: LocalDate,
    ): NoticeAcknowledgement? =
        relaxations
            .acknowledgementsInForce(KEY, scope(tenant), on)
            .minByOrNull { it.acknowledgedAt }
            ?.let { NoticeAcknowledgement(it.principal, null) }

    private fun scope(tenant: String) = PolicyScope.tenant(TenantId(UUID.fromString(tenant)))

    companion object {
        val KEY = VariationPointKey("VP-66")
        const val DEFAULT_OPTION = "DAYS_90"
    }
}
