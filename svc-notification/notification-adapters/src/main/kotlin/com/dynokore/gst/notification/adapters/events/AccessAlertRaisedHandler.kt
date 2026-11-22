package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.alerting.AccessAlertResult
import com.dynokore.gst.notification.application.alerting.HandleAccessAlertRaised
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of AccessAlertRaised (INV-REQ-895): an adapter around the framework-free
 * [HandleAccessAlertRaised]. A type other than [EVENT_TYPE] (compared by equality) or a payload the use case refuses is
 * a contract violation (platform [ContractViolation]). AccessAlertNotDelivered is not caught: the consumer retries and
 * the event is not marked processed.
 */
class AccessAlertRaisedHandler(
    private val useCase: HandleAccessAlertRaised,
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
            AccessAlertResult.RAISED -> HandleOutcome.APPLIED
            AccessAlertResult.ALREADY_OPEN, AccessAlertResult.IGNORED_OTHER_CODE -> HandleOutcome.IGNORED
        }
    }

    private fun parse(dataJson: String): Map<String, Any?> =
        try {
            mapper.readValue(dataJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JacksonException) {
            throw ContractViolation("payload is not a JSON object", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.access.AccessAlertRaised.v1"
        const val TOPIC = "gst.access.access-alert-raised.v1"
    }
}
