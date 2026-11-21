package com.dynokore.gst.submission.adapters.policy

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.application.filing.FilerOptionsAt
import com.dynokore.gst.submission.domain.filer.FilerOption
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * [FilerOptionsAt] over the configuration resolver (INV-REQ-1272): VP-11 is resolved at entity scope of the given
 * tenant on the date passed in (the adapter reads no clock). Fail closed: a resolver exception, no selected option,
 * more than one, a key [FilerOption.fromKey] refuses, or a tenant other than the bound one throws; there is no
 * default option.
 */
class PolicyFilerOptions(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
) : FilerOptionsAt {
    override fun at(
        tenant: TenantId,
        entityRef: String,
        on: LocalDate,
    ): FilerOption {
        check(tenant == tenants.current()) { "the filer option is read for the bound tenant only" }
        val scope = PolicyScope.entity(tenant, entity = entityRef)
        val selected = reader.resolve(VariationPointKey(KEY), scope, on).selected
        check(selected.size == 1) { "VP-11 must select exactly one option, got ${selected.size}" }
        return FilerOption.fromKey(selected.single().key)
    }

    companion object {
        /** VP-11, read from the generated catalogue. */
        val KEY: String = VariationPoint.VP_11.id
    }
}
