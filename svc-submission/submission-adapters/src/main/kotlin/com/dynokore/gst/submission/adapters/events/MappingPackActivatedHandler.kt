package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.compliance.PackActivation
import com.dynokore.gst.submission.application.compliance.PackActivations
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Stores each `MappingPackActivated` of rules-svc in the pack activation history (INV-AC-26739), in the inbox
 * consumer's transaction. It reads `entityRef`, `packId`, `packVersion`, `effectiveFrom`, `activatedBy`,
 * `activatedAt` and the optional `effectiveTo`; unknown members are ignored (additive contract, API-41). The payload
 * names no tenant: the row's tenant is the envelope's, and the bound tenant must be that one. A refusal is a
 * [ContractViolation] naming the member, never its value, and stores nothing. A repeated event id is IGNORED.
 */
class MappingPackActivatedHandler(
    private val tenants: TenantContext,
    private val activations: PackActivations,
) : EventHandler {
    private val mapper = ObjectMapper()

    override fun handle(event: Envelope): HandleOutcome {
        val tenant = tenants.current()
        if (tenant != event.tenant) throw ContractViolation("the bound tenant is not the tenant of the event")
        val data = parse(event.dataJson)
        val activation =
            PackActivation(
                eventId = event.id.value,
                entityRef = data.text("entityRef"),
                packId = data.text("packId"),
                packLabel = data.text("packVersion"),
                effectiveFrom = date(data.text("effectiveFrom"), "effectiveFrom"),
                effectiveTo = if (data.has("effectiveTo")) date(data.text("effectiveTo"), "effectiveTo") else null,
                activatedBy = data.text("activatedBy"),
                activatedAt = instant(data.text("activatedAt"), "activatedAt"),
            )
        return if (activations.record(tenant, activation)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("$EVENT data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("$EVENT data is not a JSON object")
        return data
    }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isBlank()) {
            throw ContractViolation("$EVENT member $name is missing or not a non-blank string")
        }
        return node.asString()
    }

    private fun date(
        text: String,
        name: String,
    ): LocalDate {
        if (!FULL_DATE.matches(text)) throw ContractViolation("$EVENT member $name is not a date")
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$EVENT member $name is not a date", e)
        }
    }

    private fun instant(
        text: String,
        name: String,
    ): Instant =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$EVENT member $name is not a date-time", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.rules.MappingPackActivated.v1"
        const val TOPIC = "gst.rules.mapping-pack-activated.v1"
        private const val EVENT = "MappingPackActivated"

        // RFC 3339 full-date: LocalDate.parse alone also takes a signed year of more than four digits.
        private val FULL_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    }
}
