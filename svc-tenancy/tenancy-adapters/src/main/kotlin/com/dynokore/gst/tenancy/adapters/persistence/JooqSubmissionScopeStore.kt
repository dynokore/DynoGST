package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.SubmissionPeriod
import com.dynokore.gst.tenancy.application.period.SubmissionScopeRecord
import com.dynokore.gst.tenancy.application.period.SubmissionScopeStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/**
 * The stored go-live date and the in-force period records of an entity of the bound tenant (jOOQ only, CS-18). It reads
 * in the transaction of the caller; forced RLS hides every other tenant's rows, so an entity of another tenant is null.
 */
class JooqSubmissionScopeStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val periods: PeriodRecordStore,
) : SubmissionScopeStore {
    override fun find(entityRef: String): SubmissionScopeRecord? {
        val row =
            dsl
                .select(ENTITY.ID, ENTITY.GO_LIVE_DATE)
                .from(ENTITY)
                .where(ENTITY.TENANT_ID.eq(tenants.current().value).and(ENTITY.REF.eq(entityRef)))
                .fetchOne() ?: return null
        val id = checkNotNull(row.get(ENTITY.ID)) { "entity.id is null" }
        return SubmissionScopeRecord(
            row.get(ENTITY.GO_LIVE_DATE),
            periods.inForce(id).map { SubmissionPeriod(it.start, it.end) },
        )
    }
}
