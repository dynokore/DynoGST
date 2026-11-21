package com.dynokore.gst.evidence.adapters.retention

import com.dynokore.gst.evidence.application.retention.NoticeAcknowledgement
import com.dynokore.gst.evidence.application.retention.NoticeWindowSource
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.RelaxationReader
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import java.time.Instant
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
        entityRef: String,
        notifiedAt: Instant,
    ): NoticeAcknowledgement? {
        val on = notifiedAt.atZone(BusinessDate.ZONE).toLocalDate()
        // minByOrNull keeps the first of equal instants: the recorded order breaks a tie
        val first = relaxations.acknowledgementsInForce(KEY, scope(tenant), on).minByOrNull { it.acknowledgedAt }
        return first?.let { NoticeAcknowledgement(it.principal, soleOperator(tenant, entityRef, it.acknowledgedAt)) }
    }

    /**
     * True exactly when VP-40 selects only the sole-operator option for the entity on the date of the acknowledgement
     * and the relaxation behind it holds an acknowledgement (INV-REQ-994); fails closed, a reader exception propagates.
     */
    private fun soleOperator(
        tenant: String,
        entityRef: String,
        acknowledgedAt: Instant,
    ): Boolean {
        val on = acknowledgedAt.atZone(BusinessDate.ZONE).toLocalDate()
        val scope = PolicyScope.entity(TenantId(UUID.fromString(tenant)), entityRef)
        val selected = policy.resolve(SINGLE_ATTESTATION, scope, on).selected.map { it.key }
        return selected == listOf(SOLE_OPERATOR_OPTION) &&
            relaxations.acknowledgementsInForce(SINGLE_ATTESTATION, scope, on).isNotEmpty()
    }

    private fun scope(tenant: String) = PolicyScope.tenant(TenantId(UUID.fromString(tenant)))

    companion object {
        val KEY = VariationPointKey("VP-66")
        private val SINGLE_ATTESTATION = VariationPointKey("VP-40")
        private const val SOLE_OPERATOR_OPTION = "SINGLE_ATTESTATION_SOLE_OPERATOR"
        const val DEFAULT_OPTION = "DAYS_90"
    }
}
