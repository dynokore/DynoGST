package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.contract.model.DraftDocument
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The pinned platform-contracts bundle makes `submissionType`, `supplierGstn`, `invoiceDate` and `gstAmount` required
 * on `DraftDocument` and declares the 403 of `checkPreIssuance` (INV-T-27313). A draft read without one of the four
 * must fail at the edge; a pre-pin type read it as null.
 */
class DraftDocumentPinTest {
    private val json = JsonMapper.builder().build()
    private val required = listOf("submissionType", "supplierGstn", "invoiceDate", "gstAmount")

    private fun complete(): ObjectNode =
        json.readTree(
            """
            {"entityRef":"E-1","currency":"SGD","totalIncludingTax":"109.00","submissionType":"TYPE_1A",
             "supplierGstn":"M90054321K","invoiceDate":"2026-03-20","gstAmount":"9.00"}
            """.trimIndent(),
        ) as ObjectNode

    private fun contract(): JsonNode =
        YAMLMapper().readTree(
            checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/payload/openapi.yaml")) {
                "gst/contracts/payload/openapi.yaml is missing"
            },
        )

    @Test
    fun `INV-AC-27310 a complete draft is read with the four members equal to the JSON values`() {
        val draft = json.readValue(complete().toString(), DraftDocument::class.java)
        draft.submissionType shouldBe DraftDocument.SubmissionType.TYPE_1A
        draft.supplierGstn shouldBe "M90054321K"
        draft.invoiceDate shouldBe LocalDate.of(2026, 3, 20)
        draft.gstAmount.compareTo(BigDecimal("9.00")) shouldBe 0
    }

    @Test
    fun `INV-AC-27310 a draft with one of the four members left out fails to read and names the member`() {
        required.forEach { member ->
            val text = complete().also { it.remove(member) }.toString()
            val failure = shouldThrow<JacksonException> { json.readValue(text, DraftDocument::class.java) }
            failure.message.orEmpty() shouldContain member
        }
    }

    @Test
    fun `INV-AC-27310 a draft with one of the four members present and null fails to read`() {
        required.forEach { member ->
            val text = complete().also { it.putNull(member) }.toString()
            shouldThrow<JacksonException> { json.readValue(text, DraftDocument::class.java) }
        }
    }

    @Test
    fun `INV-AC-27310 the pinned bundle lists the four names in required of DraftDocument`() {
        val listed = contract()["components"]["schemas"]["DraftDocument"]["required"].values().map { it.asString() }
        listed.containsAll(required) shouldBe true
    }

    @Test
    fun `INV-AC-27310 the pinned bundle declares 403 on checkPreIssuance`() {
        val operation =
            contract()["paths"]
                .properties()
                .flatMap { it.value.properties() }
                .map { it.value }
                .single { it["operationId"]?.asString() == "checkPreIssuance" }
        operation["responses"].has("403") shouldBe true
    }
}
