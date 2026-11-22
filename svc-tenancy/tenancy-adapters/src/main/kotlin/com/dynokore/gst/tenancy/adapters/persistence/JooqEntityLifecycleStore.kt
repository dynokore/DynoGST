package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.UNSUBMITTED_OVERRIDE
import com.dynokore.gst.tenancy.application.lifecycle.EntityLifecycleStore
import com.dynokore.gst.tenancy.application.lifecycle.UnsubmittedOverrideLog
import com.dynokore.gst.tenancy.domain.LifecycleState
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * The stored lifecycle state of an entity and the recorded unsubmitted-document overrides of the bound tenant (jOOQ
 * only, CS-18); forced RLS hides every other tenant's rows. Both write in the transaction of the caller.
 */
class JooqEntityLifecycleStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : EntityLifecycleStore,
    UnsubmittedOverrideLog {
    override fun setLifecycleState(
        entityRef: String,
        state: LifecycleState,
        structureVersion: Long,
        activatedOn: LocalDate?,
    ) {
        val update = dsl.update(ENTITY).set(ENTITY.LIFECYCLE_STATE, state.name)
        // COALESCE(go_live_date, activatedOn): a recorded go-live date is never overwritten.
        (
            if (activatedOn ==
                null
            ) {
                update
            } else {
                update.set(ENTITY.GO_LIVE_DATE, DSL.coalesce(ENTITY.GO_LIVE_DATE, activatedOn))
            }
        ).where(ENTITY.TENANT_ID.eq(tenants.current().value).and(ENTITY.REF.eq(entityRef)))
            .execute()
    }

    override fun record(
        entityRef: String,
        override: UnsubmittedOverride,
        on: LocalDate,
    ) {
        dsl
            .insertInto(UNSUBMITTED_OVERRIDE)
            .set(UNSUBMITTED_OVERRIDE.TENANT_ID, tenants.current().value)
            .set(UNSUBMITTED_OVERRIDE.ENTITY_REF, entityRef)
            .set(UNSUBMITTED_OVERRIDE.ACKNOWLEDGED_BY, override.acknowledgedBy)
            .set(UNSUBMITTED_OVERRIDE.STATEMENT, override.statement)
            .set(UNSUBMITTED_OVERRIDE.RECORDED_ON, on)
            .execute()
    }
}
