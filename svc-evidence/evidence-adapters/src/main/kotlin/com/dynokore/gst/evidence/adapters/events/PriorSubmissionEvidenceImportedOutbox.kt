package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.migration.PriorEvidenceEvents
import com.dynokore.gst.evidence.application.migration.PriorEvidenceImported
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * [PriorEvidenceEvents] over the transactional outbox (INV-Q-6887): appends one `PriorSubmissionEvidenceImported`
 * event per newly imported record in the caller's transaction, so it commits or rolls back with the row. The event is
 * a domain event of the real tenant and the record's entity; delivery is at-least-once and the consumer dedups.
 */
class PriorSubmissionEvidenceImportedOutbox(
    private val outbox: OutboxWriter,
) : PriorEvidenceEvents {
    private val json = JsonMapper.builder().build()

    override fun imported(
        tenant: TenantId,
        event: PriorEvidenceImported,
    ) {
        val payload =
            json.createObjectNode().apply {
                put("entityRef", event.entityRef)
                put("documentNumber", event.documentNumber)
                put("transactionDate", event.transactionDate.toString())
                put("acknowledgementRef", event.acknowledgementRef)
                put("priorSolution", event.priorSolution)
            }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, json.writeValueAsString(payload))),
            EventContext(
                tenant,
                EntityRef(event.entityRef),
                null,
                null,
                "${event.entityRef}:${event.documentNumber}:${event.transactionDate}",
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.evidence.PriorSubmissionEvidenceImported.v1"
    }
}
