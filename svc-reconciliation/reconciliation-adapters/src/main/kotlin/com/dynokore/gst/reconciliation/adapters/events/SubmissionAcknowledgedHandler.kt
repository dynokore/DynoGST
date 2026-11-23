package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The consumer of `gst.submission.submission-acknowledged.v1` for the run read model (INV-REQ-1206): records the IRAS
 * acknowledgement of a version. It runs in the inbox consumer's transaction and opens none. The payload names the
 * entity and the direction of the acknowledged document. A blank `acknowledgementId`, an unparsable `timestamp`, a
 * direction other than SALE or PURCHASE or a missing field is a ContractViolation.
 */
class SubmissionAcknowledgedHandler(
    private val writer: RunReadModelWriter,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = PayloadReading.parse(event.dataJson)
        val applied =
            writer.recordAcknowledgement(
                tenant = event.tenant.toString(),
                entityRef = PayloadReading.text(data, "entityRef"),
                direction = direction(data),
                docUuid = PayloadReading.uuid(data, "docUuid"),
                version = PayloadReading.positiveInt(data, "version"),
                acknowledgementId = PayloadReading.text(data, "acknowledgementId"),
                acknowledgedAt = timestamp(PayloadReading.text(data, "timestamp")),
            )
        return if (applied) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun direction(data: JsonNode): Direction {
        val value = PayloadReading.text(data, "direction")
        return Direction.entries.firstOrNull { it.name == value }
            ?: throw ContractViolation("direction is neither SALE nor PURCHASE")
    }

    private fun timestamp(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("timestamp is not an instant", e)
        }
}
