package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.MessageDelivery.Companion.MESSAGE_DELIVERY
import com.dynokore.gst.notification.application.delivery.MessageDeliveryRecords
import com.dynokore.gst.notification.domain.delivery.MessageDeliveryRecord
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.ZoneOffset

/**
 * The delivery records (INV-REQ-817). `insertIfAbsent` is an insert on conflict (tenant_id, event_id, position) do
 * nothing, so a repeated event writes nothing and answers false. The row takes the bound tenant (TenantContext) and
 * forced row level security refuses another. A record with no template version stores an empty template_version
 * (the column is not null).
 */
class JooqMessageDeliveryRecords(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : MessageDeliveryRecords {
    override fun insertIfAbsent(record: MessageDeliveryRecord): Boolean =
        dsl
            .insertInto(MESSAGE_DELIVERY)
            .set(MESSAGE_DELIVERY.MESSAGE_ID, record.messageId)
            .set(MESSAGE_DELIVERY.TENANT_ID, tenants.current().value)
            .set(MESSAGE_DELIVERY.EVENT_ID, record.eventId)
            .set(MESSAGE_DELIVERY.POSITION, record.position)
            .set(MESSAGE_DELIVERY.ENTITY_REF, record.entityRef)
            .set(MESSAGE_DELIVERY.CHANNEL, record.channel.name)
            .set(MESSAGE_DELIVERY.TEMPLATE, record.template)
            .set(MESSAGE_DELIVERY.TEMPLATE_VERSION, record.templateVersion.orEmpty())
            .set(MESSAGE_DELIVERY.RECIPIENT_REF, record.recipientRef)
            .set(MESSAGE_DELIVERY.STATE, record.state.name)
            .onConflict(MESSAGE_DELIVERY.TENANT_ID, MESSAGE_DELIVERY.EVENT_ID, MESSAGE_DELIVERY.POSITION)
            .doNothing()
            .execute() == 1

    override fun update(record: MessageDeliveryRecord) {
        dsl
            .update(MESSAGE_DELIVERY)
            .set(MESSAGE_DELIVERY.TEMPLATE_VERSION, record.templateVersion.orEmpty())
            .set(MESSAGE_DELIVERY.STATE, record.state.name)
            .set(MESSAGE_DELIVERY.DELIVERY_ID, record.deliveryId)
            .set(MESSAGE_DELIVERY.FAILURE_REASON, record.failureReason)
            .set(MESSAGE_DELIVERY.SENT_AT, record.sentAt?.atOffset(ZoneOffset.UTC))
            .where(MESSAGE_DELIVERY.MESSAGE_ID.eq(record.messageId))
            .execute()
    }
}
