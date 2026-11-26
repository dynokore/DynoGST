package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.retention.RetentionNotice
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper

/**
 * The message of a retention notice over the transactional outbox (INV-REQ-639, INV-AC-26996): one `MessageRequested`
 * event for notification-svc, appended in the caller's transaction and filed under the partition's entity, never the
 * tenant-wide one, with the partition as the aggregate it is ordered under. The one recipient is the custody holder
 * address;
 * the payload holds nothing else of the holder (data minimisation, OWASP ASVS V8). A notice that does not carry
 * exactly one address and exactly the three display values is refused before anything is written.
 */
class RetentionNoticeMessageOutbox(
    private val outbox: OutboxWriter,
) {
    private val json = JsonMapper.builder().build()

    fun append(
        tenant: TenantId,
        notice: RetentionNotice,
        entity: EntityRef,
    ) {
        require(notice.recipients.size == 1 && notice.recipients.single().isNotBlank()) {
            "a retention notice has exactly one recipient"
        }
        require(notice.templateParameters.keys == PARAMETERS) { "a retention notice has the three display values" }
        val payload =
            json.createObjectNode().apply {
                put("template", notice.template)
                putArray("recipients").add(notice.recipients.single())
                put("channel", notice.channel)
                putObject("templateParameters").also { parameters ->
                    notice.templateParameters.forEach { (name, value) -> parameters.put(name, value) }
                }
            }
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, json.writeValueAsString(payload))),
            EventContext(
                tenant,
                entity,
                null,
                null,
                notice.partitionId,
                null,
                CorrelationId("retention-notice:${notice.partitionId}"),
                CausationId(notice.partitionId),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.access.MessageRequested.v1"
        private val PARAMETERS = setOf("periodStart", "periodEnd", "noticeWindowEndsAt")
    }
}
