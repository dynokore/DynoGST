package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.FeedHealthState.Companion.FEED_HEALTH_STATE
import com.dynokore.gst.notification.application.alerting.AlertId
import com.dynokore.gst.notification.application.alerting.FeedAlertDraft
import com.dynokore.gst.notification.application.alerting.FeedAlertStore
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Raises and resolves the COVERAGE alert of a failed feed control (INV-REQ-910, INV-REQ-1114). The raise conflict
 * target is the open-alert unique index alert_one_open_per_key, so a repeated key returns the id of the open row and
 * inserts none. The class is always COVERAGE: any other class in a draft is refused. The resolve needs the column
 * grant on state and snoozed_until and leaves the alert open while another feed row of the same tenant, entity, period
 * and control is FAILED. Forced row level security binds both to the tenant.
 */
@Component
class JooqFeedAlerts(
    private val dsl: DSLContext,
) : FeedAlertStore {
    override fun raise(draft: FeedAlertDraft): AlertId {
        require(draft.alertClass == AlertClass.COVERAGE) { "a feed health alert is COVERAGE, not ${draft.alertClass}" }
        dsl
            .insertInto(ALERT)
            .set(ALERT.TENANT_ID, draft.tenant.value)
            .set(ALERT.ALERT_ID, UUID.randomUUID())
            .set(ALERT.ENTITY_REF, draft.entityRef)
            .set(ALERT.ALERT_CLASS, COVERAGE)
            .set(ALERT.STATE, RAISED)
            .set(ALERT.RAISED_AT, draft.raisedAt.atOffset(ZoneOffset.UTC))
            .set(ALERT.TRIGGER, draft.trigger)
            .set(ALERT.PERIOD_REF, draft.periodRef)
            .onConflict(
                ALERT.TENANT_ID,
                ALERT.ENTITY_REF,
                ALERT.ALERT_CLASS,
                DSL.coalesce(ALERT.TRIGGER, ""),
                DSL.coalesce(ALERT.PERIOD_REF, ""),
            ).where(ALERT.STATE.`in`(RAISED, SNOOZED))
            .doNothing()
            .execute()
        val id =
            dsl
                .select(ALERT.ALERT_ID)
                .from(ALERT)
                .where(ALERT.TENANT_ID.eq(draft.tenant.value))
                .and(ALERT.ENTITY_REF.eq(draft.entityRef))
                .and(ALERT.ALERT_CLASS.eq(COVERAGE))
                .and(ALERT.TRIGGER.eq(draft.trigger))
                .and(ALERT.PERIOD_REF.eq(draft.periodRef))
                .and(ALERT.STATE.`in`(RAISED, SNOOZED))
                .fetchOne(ALERT.ALERT_ID)
        return AlertId(checkNotNull(id) { "no open COVERAGE alert after the raise" }.toString())
    }

    override fun resolveIfNoFeedFailed(
        tenant: TenantId,
        entityRef: String,
        period: String,
        control: String,
        at: Instant,
    ): Boolean =
        dsl
            .update(ALERT)
            .set(ALERT.STATE, RESOLVED)
            .setNull(ALERT.SNOOZED_UNTIL)
            .where(ALERT.TENANT_ID.eq(tenant.value))
            .and(ALERT.ENTITY_REF.eq(entityRef))
            .and(ALERT.ALERT_CLASS.eq(COVERAGE))
            .and(ALERT.TRIGGER.eq(control))
            .and(ALERT.PERIOD_REF.eq(period))
            .and(ALERT.STATE.`in`(RAISED, SNOOZED))
            .andNotExists(
                DSL
                    .selectOne()
                    .from(FEED_HEALTH_STATE)
                    .where(FEED_HEALTH_STATE.TENANT_ID.eq(tenant.value))
                    .and(FEED_HEALTH_STATE.ENTITY_REF.eq(entityRef))
                    .and(FEED_HEALTH_STATE.PERIOD.eq(period))
                    .and(FEED_HEALTH_STATE.CONTROL.eq(control))
                    .and(FEED_HEALTH_STATE.STATE.eq(FAILED)),
            ).execute() > 0

    private companion object {
        val COVERAGE = AlertClass.COVERAGE.name
        const val RAISED = "RAISED"
        const val SNOOZED = "SNOOZED"
        const val RESOLVED = "RESOLVED"
        const val FAILED = "FAILED"
    }
}
