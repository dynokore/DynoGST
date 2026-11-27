package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.AcceptanceCalendarSource
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.AcceptanceEntry
import com.dynokore.platform.integrationcanon.ReleaseOrder
import com.dynokore.platform.integrationcanon.SpecVersion
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/** The configured acceptance calendar text is not a document of the published form. */
class AcceptanceCalendarInvalid(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * The acceptance calendar of each family, read from a text in the form of `acceptance-calendar.schema.json`
 * (INV-REQ-590). The published schema is open (API-41); this loader refuses every member the form does not name.
 * An absent text gives no calendar; there is no default calendar.
 */
class ConfiguredAcceptanceCalendars private constructor(
    private val byFamily: Map<SpecificationFamily, AcceptanceCalendar>,
) : AcceptanceCalendarSource {
    override fun calendarOf(family: SpecificationFamily): AcceptanceCalendar? = byFamily[family]

    companion object {
        private const val MAX_FAMILY_LENGTH = 40
        private val FULL_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

        private val mapper =
            JsonMapper
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build()

        fun parse(text: String?): ConfiguredAcceptanceCalendars {
            if (text.isNullOrBlank()) return ConfiguredAcceptanceCalendars(emptyMap())
            val document = readDocument(text)
            requireMembers(document, "document", setOf("calendars"))
            val byFamily = mutableMapOf<SpecificationFamily, AcceptanceCalendar>()
            elements(document, "calendars", "calendars").forEachIndexed { index, node ->
                val path = "calendars[$index]"
                requireMembers(node, path, setOf("family", "releaseOrder", "entries"))
                val family = family(node, "$path.family")
                if (byFamily.put(family, calendar(node, path)) != null) {
                    throw AcceptanceCalendarInvalid("$path.family: the family ${family.name} is named twice")
                }
            }
            return ConfiguredAcceptanceCalendars(byFamily.toMap())
        }

        private fun readDocument(text: String): JsonNode =
            try {
                mapper.readTree(text)
            } catch (e: JacksonException) {
                throw AcceptanceCalendarInvalid("document: not one well-formed JSON value: ${e.originalMessage}", e)
            }

        private fun requireMembers(
            node: JsonNode,
            path: String,
            known: Set<String>,
        ) {
            if (!node.isObject) throw AcceptanceCalendarInvalid("$path: must be an object")
            val unknown = node.propertyNames().filterNot { it in known }
            if (unknown.isNotEmpty()) {
                throw AcceptanceCalendarInvalid("$path: unknown member(s) ${unknown.joinToString()}")
            }
        }

        private fun elements(
            node: JsonNode,
            member: String,
            path: String,
        ): List<JsonNode> {
            val array = node.get(member)
            if (array == null || !array.isArray || array.isEmpty) {
                throw AcceptanceCalendarInvalid("$path: required, a non-empty array")
            }
            return array.toList()
        }

        private fun string(
            node: JsonNode?,
            path: String,
        ): String {
            if (node == null || !node.isString) throw AcceptanceCalendarInvalid("$path: required, a string")
            return node.stringValue()
        }

        private fun family(
            node: JsonNode,
            path: String,
        ): SpecificationFamily {
            val name = string(node.get("family"), path)
            if (name.length > MAX_FAMILY_LENGTH) {
                throw AcceptanceCalendarInvalid("$path: at most $MAX_FAMILY_LENGTH characters")
            }
            return SpecificationFamily.entries.firstOrNull { it.name == name }
                ?: throw AcceptanceCalendarInvalid("$path: '$name' is not a specification family")
        }

        private fun calendar(
            node: JsonNode,
            path: String,
        ): AcceptanceCalendar {
            val orderPath = "$path.releaseOrder"
            val versions =
                elements(node, "releaseOrder", orderPath).mapIndexed { index, code ->
                    version(code, "$orderPath[$index]")
                }
            val order = checked(orderPath) { ReleaseOrder.of(versions) }
            val entriesPath = "$path.entries"
            val entries =
                elements(node, "entries", entriesPath).mapIndexed { index, entry ->
                    entry(entry, "$entriesPath[$index]")
                }
            return checked(entriesPath) { AcceptanceCalendar.of(order, entries) }
        }

        private fun entry(
            node: JsonNode,
            path: String,
        ): AcceptanceEntry {
            requireMembers(node, path, setOf("version", "acceptedFrom", "acceptedTo"))
            val version = version(node.get("version"), "$path.version")
            val from = date(node.get("acceptedFrom"), "$path.acceptedFrom")
            // an absent member is open-ended; a present one, JSON null included, must be a date
            val to = node.get("acceptedTo")?.let { date(it, "$path.acceptedTo") }
            return checked("$path.acceptedTo") { AcceptanceEntry(version, from, to) }
        }

        private fun version(
            node: JsonNode?,
            path: String,
        ): SpecVersion {
            val code = string(node, path)
            return checked(path) { SpecVersion(code) }
        }

        private fun date(
            node: JsonNode?,
            path: String,
        ): LocalDate {
            val value = string(node, path)
            // the schema's `format: date` is an RFC 3339 full-date; LocalDate.parse alone also takes signed long years
            if (!FULL_DATE.matches(value)) throw AcceptanceCalendarInvalid("$path: '$value' is not a date yyyy-mm-dd")
            return runCatching { LocalDate.parse(value) }
                .getOrElse { throw AcceptanceCalendarInvalid("$path: '$value' is not a calendar date", it) }
        }

        /** Runs a constructor of the library and keeps its refusal as the cause. */
        private fun <T> checked(
            path: String,
            build: () -> T,
        ): T =
            try {
                build()
            } catch (e: IllegalArgumentException) {
                throw AcceptanceCalendarInvalid("$path: ${e.message}", e)
            }
    }
}
