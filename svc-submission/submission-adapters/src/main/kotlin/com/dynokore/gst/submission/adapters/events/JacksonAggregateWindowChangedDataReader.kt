package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedData
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.SPAN_UNKNOWN_PERIOD
import com.dynokore.gst.submission.application.completeness.WindowChangeState
import com.dynokore.gst.submission.application.completeness.WindowIdentity
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [AggregateWindowChangedDataReader] over Jackson. Reads `entityRef`, `prescribedPeriod`, `window.from`, `window.to`,
 * `state` by exact member name, the optional `stateVersion` (an integer of at least 1; 0 when absent) and the optional
 * `spanKnown` (a boolean; true when absent; false only for state MISSED with `prescribedPeriod`
 * [SPAN_UNKNOWN_PERIOD]). Reads the optional pair `businessUnitRef` (a non-blank string of at most 40 characters) and
 * `aggregateType` (exactly POS, STI or PCP): both present, or both absent for [WindowIdentity.NONE]. A missing or
 * malformed member is a [ContractViolation] whose message names the member, never its value.
 */
class JacksonAggregateWindowChangedDataReader : AggregateWindowChangedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): AggregateWindowChangedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AggregateWindowChanged data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("AggregateWindowChanged data is not a JSON object")
        }
        val window = windowOf(data)
        val read =
            AggregateWindowChangedData(
                entityRef = data.text("entityRef", "entityRef"),
                prescribedPeriod = data.text("prescribedPeriod", "prescribedPeriod"),
                from = dateOf(window.text("from", "window.from")),
                to = dateOf(window.text("to", "window.to")),
                state = stateOf(data.text("state", "state")),
                stateVersion = versionOf(data),
                identity = identityOf(data),
                spanKnown = spanKnownOf(data),
            )
        requireUnknownSpanShape(read)
        return read
    }

    private fun identityOf(data: JsonNode): WindowIdentity {
        val hasUnit = data.has("businessUnitRef")
        if (hasUnit != data.has("aggregateType")) {
            throw ContractViolation("AggregateWindowChanged businessUnitRef and aggregateType must come together")
        }
        return if (hasUnit) namedIdentityOf(data) else WindowIdentity.NONE
    }

    private fun namedIdentityOf(data: JsonNode): WindowIdentity {
        val unit = data.text("businessUnitRef", "businessUnitRef")
        if (unit.isBlank() || unit.codePointCount(0, unit.length) > MAX_BUSINESS_UNIT_LENGTH) {
            throw ContractViolation("AggregateWindowChanged businessUnitRef is blank or too long")
        }
        val type = data.text("aggregateType", "aggregateType")
        if (type !in AGGREGATE_TYPES) {
            throw ContractViolation("AggregateWindowChanged aggregateType is not a known aggregate type")
        }
        return WindowIdentity(unit, type)
    }

    private fun spanKnownOf(data: JsonNode): Boolean {
        val node = data.get("spanKnown") ?: return true
        if (!node.isBoolean) {
            throw ContractViolation("AggregateWindowChanged spanKnown is not a boolean")
        }
        return node.asBoolean()
    }

    private fun requireUnknownSpanShape(data: AggregateWindowChangedData) {
        if (data.spanKnown) return
        if (data.state != WindowChangeState.MISSED || data.prescribedPeriod != SPAN_UNKNOWN_PERIOD) {
            throw ContractViolation("AggregateWindowChanged spanKnown false does not match state or prescribedPeriod")
        }
    }

    private fun windowOf(data: JsonNode): JsonNode {
        val window = data.get("window")
        if (window == null || !window.isObject) {
            throw ContractViolation("AggregateWindowChanged data member window is missing or not an object")
        }
        return window
    }

    private fun JsonNode.text(
        name: String,
        label: String,
    ): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("AggregateWindowChanged data member $label is missing or not a non-empty string")
        }
        return node.asString()
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("AggregateWindowChanged window bound is not a date", e)
        }

    private fun stateOf(text: String): WindowChangeState =
        WindowChangeState.entries.firstOrNull { it.name == text }
            ?: throw ContractViolation("AggregateWindowChanged state is not a known state")

    private fun versionOf(data: JsonNode): Long {
        val node = data.get("stateVersion") ?: return 0L
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
            throw ContractViolation("AggregateWindowChanged stateVersion is not an integer of at least 1")
        }
        return node.asLong()
    }

    private companion object {
        const val MAX_BUSINESS_UNIT_LENGTH = 40
        val AGGREGATE_TYPES = setOf("POS", "STI", "PCP")
    }
}
