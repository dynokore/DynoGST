package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedData
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [IdentityTripleCorrectedDataReader] over Jackson. The four strings are non-empty and at most 40 characters (the
 * contract maximum), `effectiveFrom` is an ISO local date and `accessPointEscalation` is the boolean true (the
 * contract constant). Unknown members are ignored. A violation names the member, never its value.
 */
class JacksonIdentityTripleCorrectedDataReader : IdentityTripleCorrectedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): IdentityTripleCorrectedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("IdentityTripleCorrected data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("IdentityTripleCorrected data is not a JSON object")
        }
        return IdentityTripleCorrectedData(
            entityRef = data.text("entityRef"),
            constitution = data.text("constitution"),
            effectiveFrom = dateOf(data.text("effectiveFrom", MAX_LENGTH_UNBOUNDED)),
            changeRef = data.text("changeRef"),
            accessPointEscalation = escalationOf(data),
        )
    }

    private fun escalationOf(data: JsonNode): Boolean {
        val node = data.get("accessPointEscalation")
        if (node == null || !node.isBoolean || !node.asBoolean()) {
            throw ContractViolation("IdentityTripleCorrected data member accessPointEscalation is missing or not true")
        }
        return true
    }

    private fun JsonNode.text(
        name: String,
        max: Int = MAX_LENGTH,
    ): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().length !in 1..max) {
            throw ContractViolation("IdentityTripleCorrected data member $name is missing or not a valid string")
        }
        return node.asString()
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("IdentityTripleCorrected effectiveFrom is not a date", e)
        }

    private companion object {
        const val MAX_LENGTH = 40
        const val MAX_LENGTH_UNBOUNDED = 10_000
    }
}
