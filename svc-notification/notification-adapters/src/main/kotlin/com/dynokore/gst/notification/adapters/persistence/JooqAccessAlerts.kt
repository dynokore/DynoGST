package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.AlertRecipient.Companion.ALERT_RECIPIENT
import com.dynokore.gst.notification.application.alerting.AccessAlertDraft
import com.dynokore.gst.notification.application.alerting.AccessAlertStore
import com.dynokore.gst.notification.domain.alerting.AlertClass
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * Stores the ACC-001 SECURITY alert and its recipients (INV-REQ-895). Inside the one transaction of the caller (the
 * consumer's) it inserts the notification.alert row with the conflict target of the open-alert unique index
 * alert_one_open_per_key and, only when that insert wrote a row, one notification.alert_recipient row per recipient.
 * A repeated key is an answer (false), never an exception. The class is always SECURITY: any other class in a draft is
 * refused. Forced row level security binds both tables to the tenant.
 */
@Component
class JooqAccessAlerts(
    private val dsl: DSLContext,
) : AccessAlertStore {
    override fun raise(draft: AccessAlertDraft): Boolean {
        require(draft.alertClass == AlertClass.SECURITY) { "an access alert is SECURITY, not ${draft.alertClass}" }
        val alertId = UUID.randomUUID()
        val inserted =
            dsl
                .insertInto(ALERT)
                .set(ALERT.TENANT_ID, draft.tenant.value)
                .set(ALERT.ALERT_ID, alertId)
                .set(ALERT.ENTITY_REF, draft.entityRef)
                .set(ALERT.ALERT_CLASS, SECURITY)
                .set(ALERT.STATE, RAISED)
                .set(ALERT.RAISED_AT, draft.raisedAt.atOffset(ZoneOffset.UTC))
                .set(ALERT.TRIGGER, draft.trigger)
                .set(ALERT.PERIOD_REF, null as String?)
                .onConflict(
                    ALERT.TENANT_ID,
                    ALERT.ENTITY_REF,
                    ALERT.ALERT_CLASS,
                    DSL.coalesce(ALERT.TRIGGER, ""),
                    DSL.coalesce(ALERT.PERIOD_REF, ""),
                ).where(ALERT.STATE.`in`(RAISED, SNOOZED))
                .doNothing()
                .execute() == 1
        if (inserted) {
            draft.recipients.forEach { user ->
                dsl
                    .insertInto(ALERT_RECIPIENT)
                    .set(ALERT_RECIPIENT.TENANT_ID, draft.tenant.value)
                    .set(ALERT_RECIPIENT.ALERT_ID, alertId)
                    .set(ALERT_RECIPIENT.USER_ID, user)
                    .execute()
            }
        }
        return inserted
    }

    private companion object {
        const val SECURITY = "SECURITY"
        const val RAISED = "RAISED"
        const val SNOOZED = "SNOOZED"
    }
}
