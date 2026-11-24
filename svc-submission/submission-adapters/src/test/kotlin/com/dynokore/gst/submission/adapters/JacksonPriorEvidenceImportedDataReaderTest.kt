package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPriorEvidenceImportedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The PriorSubmissionEvidenceImported reader (INV-AC-28453). */
class JacksonPriorEvidenceImportedDataReaderTest {
    private val reader = JacksonPriorEvidenceImportedDataReader()

    private val members =
        linkedMapOf(
            "entityRef" to "\"E-1\"",
            "documentNumber" to "\"INV-100\"",
            "transactionDate" to "\"2026-02-27\"",
            "acknowledgementRef" to "\"ACK-1\"",
            "priorSolution" to "\"P1\"",
        )

    private fun json(
        without: String? = null,
        override: Pair<String, String>? = null,
        extra: String = "",
    ) = (members - setOfNotNull(without))
        .let { m -> if (override != null) m + override else m }
        .entries
        .joinToString(",", "{", "$extra}") { "\"${it.key}\":${it.value}" }

    @Test
    fun `INV-AC-28453 a valid payload gives the five members and an extra property is tolerated`() {
        val d = reader.read(json(extra = ",\"importedBy\":\"u\""))
        d.entityRef shouldBe "E-1"
        d.documentNumber shouldBe "INV-100"
        d.transactionDate shouldBe LocalDate.of(2026, 2, 27)
        d.acknowledgementRef shouldBe "ACK-1"
        d.priorSolution shouldBe "P1"
    }

    @Test
    fun `INV-AC-28453 each missing member is a ContractViolation naming it`() {
        for (m in members.keys) {
            shouldThrow<ContractViolation> { reader.read(json(without = m)) }.message.orEmpty() shouldContain m
        }
    }

    @Test
    fun `INV-AC-28453 a malformed member never has its value in the message`() {
        shouldThrow<ContractViolation> { reader.read(json(override = "transactionDate" to "\"27/02/2026-SECRET\"")) }
            .message
            .orEmpty()
            .let {
                it shouldContain "transactionDate"
                it shouldNotContain "SECRET"
            }
        shouldThrow<ContractViolation> { reader.read(json(override = "acknowledgementRef" to "7")) }
        shouldThrow<ContractViolation> { reader.read(json(override = "priorSolution" to "\" \"")) }
        shouldThrow<ContractViolation> { reader.read("[]") }
        shouldThrow<ContractViolation> { reader.read("{not json") }
    }
}
