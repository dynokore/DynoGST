package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.compliance.PackActivation
import com.dynokore.gst.submission.application.compliance.PackActivations
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.ZoneOffset
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PackActivation as PackActivationTable

/**
 * [PackActivations] over the append-only `submission.pack_activation` (INV-AC-26739), in the open tenant transaction.
 * A repeat of (tenant, event id) inserts nothing; the tenant is in the statement as well as in row level security.
 */
class JooqPackActivations(
    private val dsl: DSLContext,
) : PackActivations {
    override fun record(
        tenant: TenantId,
        activation: PackActivation,
    ): Boolean {
        val p = PackActivationTable.PACK_ACTIVATION
        return dsl
            .insertInto(p)
            .set(p.TENANT_ID, tenant.value)
            .set(p.EVENT_ID, activation.eventId)
            .set(p.ENTITY_REF, activation.entityRef)
            .set(p.PACK_ID, activation.packId)
            .set(p.PACK_LABEL, activation.packLabel)
            .set(p.EFFECTIVE_FROM, activation.effectiveFrom)
            .set(p.EFFECTIVE_TO, activation.effectiveTo)
            .set(p.ACTIVATED_BY, activation.activatedBy)
            .set(p.ACTIVATED_AT, activation.activatedAt.atOffset(ZoneOffset.UTC))
            .onConflict(p.TENANT_ID, p.EVENT_ID)
            .doNothing()
            .execute() == 1
    }
}
