package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.FeedHealthState.Companion.FEED_HEALTH_STATE
import com.dynokore.gst.notification.application.alerting.AlertId
import com.dynokore.gst.notification.application.alerting.FeedHealthState
import com.dynokore.gst.notification.application.alerting.FeedHealthStates
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The feed health state per tenant, feed, entityRef, period and control (INV-REQ-910). A missing stateVersion is stored
 * as 0 and read back as null. Forced row level security binds every statement to the tenant; the runtime role holds
 * select, insert and update only.
 */
@Component
class JooqFeedHealthStates(
    private val dsl: DSLContext,
    private val clock: Clock,
) : FeedHealthStates {
    override fun find(
        tenant: TenantId,
        feed: String,
        entityRef: String,
        period: String,
        control: String,
    ): FeedHealthState? =
        dsl
            .selectFrom(FEED_HEALTH_STATE)
            .where(FEED_HEALTH_STATE.TENANT_ID.eq(tenant.value))
            .and(FEED_HEALTH_STATE.FEED.eq(feed))
            .and(FEED_HEALTH_STATE.ENTITY_REF.eq(entityRef))
            .and(FEED_HEALTH_STATE.PERIOD.eq(period))
            .and(FEED_HEALTH_STATE.CONTROL.eq(control))
            .fetchOne()
            ?.let {
                FeedHealthState(
                    tenant,
                    feed,
                    entityRef,
                    period,
                    control,
                    checkNotNull(it.state),
                    it.stateVersion?.takeIf { v -> v > 0 }?.toLong(),
                    it.alertId?.let { id -> AlertId(id.toString()) },
                    it.recipients?.filterNotNull().orEmpty(),
                )
            }

    override fun save(state: FeedHealthState) {
        val version = (state.stateVersion ?: 0L).toInt()
        val alert = state.alertId?.let { UUID.fromString(it.value) }
        val recipients: Array<String?>? =
            if (state.recipients.isEmpty()) null else state.recipients.toTypedArray<String?>()
        val now = OffsetDateTime.now(clock)
        dsl
            .insertInto(FEED_HEALTH_STATE)
            .set(FEED_HEALTH_STATE.TENANT_ID, state.tenant.value)
            .set(FEED_HEALTH_STATE.FEED, state.feed)
            .set(FEED_HEALTH_STATE.ENTITY_REF, state.entityRef)
            .set(FEED_HEALTH_STATE.PERIOD, state.period)
            .set(FEED_HEALTH_STATE.CONTROL, state.control)
            .set(FEED_HEALTH_STATE.STATE, state.state)
            .set(FEED_HEALTH_STATE.STATE_VERSION, version)
            .set(FEED_HEALTH_STATE.ALERT_ID, alert)
            .set(FEED_HEALTH_STATE.RECIPIENTS, recipients)
            .set(FEED_HEALTH_STATE.UPDATED_AT, now)
            .onConflict(
                FEED_HEALTH_STATE.TENANT_ID,
                FEED_HEALTH_STATE.FEED,
                FEED_HEALTH_STATE.ENTITY_REF,
                FEED_HEALTH_STATE.PERIOD,
                FEED_HEALTH_STATE.CONTROL,
            ).doUpdate()
            .set(FEED_HEALTH_STATE.STATE, state.state)
            .set(FEED_HEALTH_STATE.STATE_VERSION, version)
            .set(FEED_HEALTH_STATE.ALERT_ID, alert)
            .set(FEED_HEALTH_STATE.RECIPIENTS, recipients)
            .set(FEED_HEALTH_STATE.UPDATED_AT, now)
            .execute()
    }
}
