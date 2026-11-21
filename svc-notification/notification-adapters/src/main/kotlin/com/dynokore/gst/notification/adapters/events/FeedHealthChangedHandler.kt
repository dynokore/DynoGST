package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.alerting.FeedHealthResult
import com.dynokore.gst.notification.application.alerting.HandleFeedHealthChanged
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of FeedHealthChanged (INV-REQ-910): an adapter around the framework-free
 * [HandleFeedHealthChanged]. It runs inside the consumer's transaction. A type other than [EVENT_TYPE] (compared by
 * equality) or a payload the use case refuses is a contract violation (platform [ContractViolation]) that goes to the
 * dead letter at once and is never retried. A stale event is IGNORED.
 */
class FeedHealthChangedHandler(
    private val useCase: HandleFeedHealthChanged,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != EVENT_TYPE) throw ContractViolation("unexpected event type ${event.type}", null)
        val payload = parse(event.dataJson)
        val result =
            try {
                useCase.handle(event.tenant, payload)
            } catch (e: PayloadViolation) {
                throw ContractViolation(e.message ?: "payload refused", e)
            }
        return when (result) {
            FeedHealthResult.APPLIED_RAISED, FeedHealthResult.APPLIED_CLEARED -> HandleOutcome.APPLIED
            FeedHealthResult.IGNORED_STALE -> HandleOutcome.IGNORED
        }
    }

    private fun parse(dataJson: String): Map<String, Any?> =
        try {
            mapper.readValue(dataJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JacksonException) {
            throw ContractViolation("payload is not a JSON object", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.ingestion.FeedHealthChanged.v1"
        const val TOPIC = "gst.ingestion.feed-health-changed.v1"
    }
}
