package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WindowPartState
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.gst.submission.application.completeness.WindowIdentity
import com.dynokore.gst.submission.application.completeness.WindowPartStates
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [WindowPartStates] over `submission.window_part_state`: one statement, `insert ... on conflict do update ... where
 * stored state_version < new`, in one transaction of the bound tenant (INV-REQ-792, ruled case 4). FAILING is stored
 * as LATE, NOT_FAILING as CLOSED, OPEN as OPEN, MISSED as MISSED. The row key includes the business unit and category,
 * so the event of one window never changes the row of another (INV-REQ-413). `missed_at` is the transaction instant of the write
 * that made the row MISSED: a later MISSED keeps it and any other state clears it (INV-REQ-413). Values are never
 * logged.
 */
class JooqWindowPartStateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : WindowPartStates {
    private val w = WindowPartState.WINDOW_PART_STATE

    @Suppress("LongParameterList")
    override fun record(
        tenant: TenantId,
        entityRef: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        identity: WindowIdentity,
        state: StoredState,
        stateVersion: Long,
    ): Boolean {
        val column =
            when (state) {
                StoredState.FAILING -> "LATE"
                StoredState.NOT_FAILING -> "CLOSED"
                StoredState.OPEN -> "OPEN"
                StoredState.MISSED -> "MISSED"
            }
        val missed = state == StoredState.MISSED
        val now = DSL.currentOffsetDateTime()
        val noInstant = DSL.inline(null as OffsetDateTime?, w.MISSED_AT.dataType)
        // In the update, a column of w is the row as stored before this write.
        val keptOrNow = DSL.coalesce(DSL.`when`(w.STATE.eq("MISSED"), w.MISSED_AT), now)
        return runner.inTransaction {
            dsl
                .insertInto(w)
                .set(w.TENANT_ID, tenant.value)
                .set(w.ENTITY_REF, entityRef)
                .set(w.PERIOD_REF, period)
                .set(w.PART_FROM, from)
                .set(w.PART_TO, to)
                .set(w.BUSINESS_UNIT_REF, identity.businessUnitRef)
                .set(w.AGGREGATE_TYPE, identity.aggregateType)
                .set(w.STATE, column)
                .set(w.STATE_VERSION, stateVersion)
                .set(w.MISSED_AT, if (missed) now else noInstant)
                .onConflict(
                    w.TENANT_ID,
                    w.ENTITY_REF,
                    w.PERIOD_REF,
                    w.PART_FROM,
                    w.PART_TO,
                    w.BUSINESS_UNIT_REF,
                    w.AGGREGATE_TYPE,
                ).doUpdate()
                .set(w.STATE, column)
                .set(w.STATE_VERSION, stateVersion)
                .set(w.MISSED_AT, if (missed) keptOrNow else noInstant)
                .where(w.STATE_VERSION.lt(stateVersion))
                .execute() == 1
        }
    }
}
