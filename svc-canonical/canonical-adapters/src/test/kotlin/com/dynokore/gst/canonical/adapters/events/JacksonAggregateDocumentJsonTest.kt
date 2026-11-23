package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.aggregate.AggregateAmounts
import com.dynokore.gst.canonical.application.aggregate.MergedLine
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.TaxSubtotal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

class JacksonAggregateDocumentJsonTest {
    private val mapper = JsonMapper.builder().build()
    private val identity = SupplierIdentity("sender-1", "supplier-1", "M2-1234567-X")
    private val lines = listOf(MergedLine("S", BigDecimal("100.00"), BigDecimal("0.09")))
    private val amounts =
        AggregateAmounts(
            listOf(TaxSubtotal("S", BigDecimal("100.00"), BigDecimal("9.00"))),
            BigDecimal("100.00"),
            BigDecimal("9.00"),
            BigDecimal("109.00"),
        )

    private fun build(type: String): JsonNode =
        mapper.readTree(JacksonAggregateDocumentJson().build(lines, amounts, false, type, identity))

    private fun party(lit: String) =
        mapper.readTree("""{"name":"$lit","identifierValue":"$lit","address":{"line1":"$lit","postalCode":"$lit"}}""")

    private fun triple() =
        mapper.readTree("""{"senderId":"sender-1","supplierId":"supplier-1","supplierGstn":"M2-1234567-X"}""")

    private fun checkBuyerLiteral(type: String) {
        val doc = build(type)
        val parties = doc["parties"]["value"]
        parties["buyer"] shouldBe party(type)
        parties["supplier"] shouldBe mapper.readTree("""{"gstn":"M2-1234567-X"}""")
        doc["identityTriple"]["value"] shouldBe triple()
    }

    @Test
    @DisplayName("INV-AC-26393 POS aggregate gives POS in the four buyer members")
    fun `INV-AC-26393 POS aggregate gives POS in the four buyer members`() = checkBuyerLiteral("POS")

    @Test
    @DisplayName("INV-AC-26393 STI aggregate gives STI in the four buyer members")
    fun `INV-AC-26393 STI aggregate gives STI in the four buyer members`() = checkBuyerLiteral("STI")

    @Test
    fun `PCP aggregate gives PCP in the five supplier members and an empty buyer`() {
        val doc = build("PCP")
        val parties = doc["parties"]["value"]
        parties["supplier"] shouldBe
            mapper.readTree(
                """{"name":"PCP","identifierValue":"PCP","gstn":"PCP","address":{"line1":"PCP","postalCode":"PCP"}}""",
            )
        parties["buyer"] shouldBe mapper.readTree("{}")
        doc["identityTriple"]["value"] shouldBe triple()
    }

    @Test
    fun `a value outside the table throws and no tree is returned`() {
        listOf("pos", "POS ", "", "Sti", "PCP\n").forEach {
            assertThrows<IllegalArgumentException> {
                JacksonAggregateDocumentJson().build(lines, amounts, false, it, identity)
            }
        }
    }

    @Test
    fun `both POS and STI is sent as POS and the document carries POS, never STI`() {
        val text = JacksonAggregateDocumentJson().build(lines, amounts, false, "POS", identity)
        val doc = mapper.readTree(text)
        doc["aggregateType"]["value"].asString() shouldBe "POS"
        doc["parties"]["value"]["buyer"] shouldBe party("POS")
        text.contains("STI") shouldBe false
    }
}
