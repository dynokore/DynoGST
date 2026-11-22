package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonIdentityTripleCorrectedDataReader
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedData
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The IdentityTripleCorrected reader (INV-AC-26836). */
class JacksonIdentityTripleCorrectedDataReaderTest {
    private val reader = JacksonIdentityTripleCorrectedDataReader()
    private val strings = listOf("entityRef", "constitution", "changeRef")
    private val all = listOf("entityRef", "constitution", "effectiveFrom", "changeRef", "accessPointEscalation")

    private fun json(overrides: Map<String, String> = emptyMap()): String {
        val base =
            linkedMapOf(
                "entityRef" to "\"SENT_E1\"",
                "constitution" to "\"SENT_C1\"",
                "effectiveFrom" to "\"2026-03-01\"",
                "changeRef" to "\"SENT_R1\"",
                "accessPointEscalation" to "true",
            )
        base.putAll(overrides)
        return base.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun without(member: String): String {
        val node =
            json()
                .removePrefix("{")
                .removeSuffix("}")
                .split(",")
                .filterNot { it.startsWith("\"$member\"") }
        return node.joinToString(",", "{", "}")
    }

    private fun violation(
        payload: String,
        member: String? = null,
    ): ContractViolation {
        val e = shouldThrow<ContractViolation> { reader.read(payload) }
        val message = e.message.orEmpty()
        if (member != null) message shouldContain member
        listOf("SENT_E1", "SENT_C1", "SENT_R1", "2026-03-01", "x".repeat(41)).forEach { message shouldNotContain it }
        return e
    }

    @Test
    fun `INV-AC-26836 case 5 a valid payload with an extra member is read`() {
        reader.read(json(mapOf("x" to "1"))) shouldBe
            IdentityTripleCorrectedData("SENT_E1", "SENT_C1", LocalDate.of(2026, 3, 1), "SENT_R1", true)
    }

    @Test
    fun `INV-AC-26836 cases 6 to 10 a missing member is a ContractViolation naming the member`() {
        all.forEach { violation(without(it), it) }
    }

    @Test
    fun `INV-AC-26836 case 11 an empty string for each of the four strings`() {
        strings.forEach { violation(json(mapOf(it to "\"\"")), it) }
        violation(json(mapOf("effectiveFrom" to "\"\"")), "effectiveFrom")
    }

    @Test
    fun `INV-AC-26836 case 12 a 41-character string is refused and a 40-character string accepted`() {
        strings.forEach {
            violation(json(mapOf(it to "\"${"x".repeat(41)}\"")), it)
            reader.read(json(mapOf(it to "\"${"y".repeat(40)}\""))) // accepted
        }
    }

    @Test
    fun `INV-AC-26836 case 13 effectiveFrom that is no date`() {
        listOf("\"2026-13-01\"", "\"26-01-01\"", "\"\"").forEach {
            violation(json(mapOf("effectiveFrom" to it)), "effectiveFrom")
        }
    }

    @Test
    fun `INV-AC-26836 case 14 accessPointEscalation false, a string or null`() {
        listOf("false", "\"true\"", "null").forEach {
            violation(json(mapOf("accessPointEscalation" to it)), "accessPointEscalation")
        }
    }

    @Test
    fun `INV-AC-26836 case 15 a payload that is not JSON and a JSON array`() {
        violation("not json SENT_E1")
        violation("[\"SENT_E1\"]")
    }
}
