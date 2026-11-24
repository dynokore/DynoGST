package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.PriorSolutionHoldRecord
import com.dynokore.gst.submission.application.migration.PriorSolutionHold
import com.dynokore.gst.submission.application.migration.PriorSolutionHoldStore
import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.ZoneOffset
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PriorSolutionHold as HoldTable
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PriorSolutionHoldResolution as ResolutionTable

/**
 * [PriorSolutionHoldStore] over `submission.prior_solution_hold` (INV-REQ-1110), in the caller's open tenant
 * transaction. The table is insert-only: [raise] is an insert that does nothing on a repeat of the key, then a read of
 * the stored row; [open] is a left join against the resolution table and keeps the hold with no resolution row.
 */
class JooqPriorSolutionHoldStore(
    private val dsl: DSLContext,
) : PriorSolutionHoldStore {
    private val h = HoldTable.PRIOR_SOLUTION_HOLD
    private val r = ResolutionTable.PRIOR_SOLUTION_HOLD_RESOLUTION

    override fun raise(hold: PriorSolutionHold): PriorSolutionHold {
        dsl
            .insertInto(h)
            .set(h.TENANT_ID, hold.tenant.value)
            .set(h.DOC_UUID, hold.docUuid)
            .set(h.VERSION, hold.version)
            .set(h.ENTITY_REF, hold.entityRef)
            .set(h.REASON, hold.reason.name)
            .set(h.CODE, hold.code)
            .set(h.RAISED_AT, hold.raisedAt.atOffset(ZoneOffset.UTC))
            .onConflict(h.TENANT_ID, h.DOC_UUID, h.VERSION)
            .doNothing()
            .execute()
        return dsl
            .selectFrom(h)
            .where(h.TENANT_ID.eq(hold.tenant.value))
            .and(h.DOC_UUID.eq(hold.docUuid))
            .and(h.VERSION.eq(hold.version))
            .fetchOne()
            ?.let(::row)
            ?: error("hold row missing after raise: ${hold.docUuid} v${hold.version}")
    }

    override fun open(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): PriorSolutionHold? =
        dsl
            .select(h.asterisk())
            .from(h)
            .leftJoin(r)
            .on(r.TENANT_ID.eq(h.TENANT_ID))
            .and(r.DOC_UUID.eq(h.DOC_UUID))
            .and(r.VERSION.eq(h.VERSION))
            .where(h.TENANT_ID.eq(tenant.value))
            .and(h.DOC_UUID.eq(docUuid))
            .and(h.VERSION.eq(version))
            .and(r.TENANT_ID.isNull)
            .fetchOneInto(h)
            ?.let(::row)

    private fun row(rec: PriorSolutionHoldRecord) =
        PriorSolutionHold(
            TenantId(checkNotNull(rec.tenantId)),
            checkNotNull(rec.docUuid),
            checkNotNull(rec.version),
            checkNotNull(rec.entityRef),
            HoldReason.valueOf(checkNotNull(rec.reason)),
            checkNotNull(rec.code),
            checkNotNull(rec.raisedAt).toInstant(),
        )
}
