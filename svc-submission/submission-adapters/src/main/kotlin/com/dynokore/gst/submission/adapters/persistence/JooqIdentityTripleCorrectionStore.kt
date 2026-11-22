package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.IdentityTripleCorrection
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedData
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrections
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [IdentityTripleCorrections] over `submission.identity_triple_correction` in one transaction of the bound tenant:
 * one `insert ... on conflict do nothing` statement, so a repeat never overwrites the first stored row. Forced row
 * level security applies. Values are never logged.
 */
class JooqIdentityTripleCorrectionStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : IdentityTripleCorrections {
    private val c = IdentityTripleCorrection.IDENTITY_TRIPLE_CORRECTION

    override fun record(
        tenant: TenantId,
        data: IdentityTripleCorrectedData,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(c)
                .set(c.TENANT_ID, tenant.value)
                .set(c.ENTITY_REF, data.entityRef)
                .set(c.CONSTITUTION, data.constitution)
                .set(c.CHANGE_REF, data.changeRef)
                .set(c.EFFECTIVE_FROM, data.effectiveFrom)
                .set(c.ACCESS_POINT_ESCALATION, data.accessPointEscalation)
                .onConflict(c.TENANT_ID, c.ENTITY_REF, c.CONSTITUTION, c.CHANGE_REF)
                .doNothing()
                .execute() == 1
        }

    override fun find(
        tenant: TenantId,
        entityRef: String,
        constitution: String,
        changeRef: String,
    ): IdentityTripleCorrectedData? =
        runner.inTransaction {
            dsl
                .selectFrom(c)
                .where(c.TENANT_ID.eq(tenant.value))
                .and(c.ENTITY_REF.eq(entityRef))
                .and(c.CONSTITUTION.eq(constitution))
                .and(c.CHANGE_REF.eq(changeRef))
                .fetchOne()
                ?.let {
                    IdentityTripleCorrectedData(
                        entityRef = checkNotNull(it.get(c.ENTITY_REF)),
                        constitution = checkNotNull(it.get(c.CONSTITUTION)),
                        effectiveFrom = checkNotNull(it.get(c.EFFECTIVE_FROM)),
                        changeRef = checkNotNull(it.get(c.CHANGE_REF)),
                        accessPointEscalation = checkNotNull(it.get(c.ACCESS_POINT_ESCALATION)),
                    )
                }
        }
}
