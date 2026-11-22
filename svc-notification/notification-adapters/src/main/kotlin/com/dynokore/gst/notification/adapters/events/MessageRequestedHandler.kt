package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.delivery.DeliverRequestedMessage
import com.dynokore.gst.notification.application.delivery.MessageChannels
import com.dynokore.gst.notification.application.delivery.MessageDeliveryRecords
import com.dynokore.gst.notification.application.delivery.TemplateVersions
import com.dynokore.gst.notification.domain.delivery.MessageDeliveryRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of MessageRequested (INV-REQ-817, INV-REQ-2762): an adapter around the framework-free
 * [DeliverRequestedMessage]. It runs inside the consumer's transaction. A wrong type, a payload that is not a JSON
 * object or a payload the use case refuses is a contract violation that goes to the dead letter at once. Violation
 * texts name a field only, never a value, so the sealed passcode and the addresses are never copied.
 */
class MessageRequestedHandler(
    private val records: MessageDeliveryRecords,
    private val channels: MessageChannels,
    private val templates: TemplateVersions,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : EventHandler {
    /** Counts the rows written for one event; one instance per event, so no state is shared between events. */
    private class Counting(
        private val inner: MessageDeliveryRecords,
    ) : MessageDeliveryRecords {
        var written = 0

        override fun insertIfAbsent(record: MessageDeliveryRecord): Boolean =
            inner.insertIfAbsent(record).also { if (it) written++ }

        override fun update(record: MessageDeliveryRecord) = inner.update(record)
    }

    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != EVENT_TYPE) throw ContractViolation("unexpected event type ${event.type}", null)
        val payload = parse(event.dataJson)
        val counting = Counting(records)
        try {
            DeliverRequestedMessage(
                counting,
                channels,
                templates,
                clock,
            ).handle(event.tenant, event.entity.value, event.id.value, payload)
        } catch (e: PayloadViolation) {
            throw ContractViolation(e.message ?: "payload refused", e)
        }
        return if (counting.written > 0) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun parse(dataJson: String): Map<String, Any?> =
        try {
            mapper.readValue(dataJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JacksonException) {
            throw ContractViolation("payload is not a JSON object", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.access.MessageRequested.v1"
        const val TOPIC = "gst.access.message-requested.v1"
    }
}
