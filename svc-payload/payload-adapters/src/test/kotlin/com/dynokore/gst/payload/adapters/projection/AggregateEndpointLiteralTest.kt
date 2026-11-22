package com.dynokore.gst.payload.adapters.projection

import com.dynokore.gst.payload.adapters.build.ActivePackRead
import com.dynokore.gst.payload.adapters.build.PintSgPayloadProjector
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.gst.payload.domain.EndpointId
import com.dynokore.gst.payload.domain.EndpointIdResult
import com.dynokore.gst.payload.domain.SubmissionType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-26396: the aggregate literal is the customer endpoint ID (IBT-049) of an aggregate (INV-REQ-412). */
class AggregateEndpointLiteralTest {
    private val buyerPath = "/Invoice/cac:AccountingCustomerParty/cac:Party/cbc:EndpointID"
    private val supplierPath = "/Invoice/cac:AccountingSupplierParty/cac:Party/cbc:EndpointID"

    private fun entry(type: String) = """{"aggregateType":"$type","businessTermId":"IBT-022","literal":"$type"}"""

    private val pack = "{\"aggregateDefaults\":[${entry("POS")},${entry("STI")},${entry("PCP")}]}"

    private fun document(aggregateType: String?): String {
        val doc =
            """
            {"documentTypeCode":{"value":"380","classification":"NONE"},
             "parties":{"value":{
               "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A","name":"Acme"},
               "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Buyer"}},"classification":"IDENTIFYING"},
             "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
             "documentDate":{"value":"2026-03-20","classification":"NONE"},
             "invoiceNote":{"value":"Thanks","classification":"NONE"},
             "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
               "classification":"IDENTIFYING"}}
            """.trimIndent()
        val prefix = aggregateType?.let { "{\"aggregateType\":{\"value\":\"$it\"}," } ?: return doc
        return doc.replaceFirst("{", prefix)
    }

    private fun project(
        aggregateType: String?,
        aggregateId: String?,
    ): String {
        val projector =
            PintSgPayloadProjector({ "PINT_SG" }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), pack) })
        val input = BuildInput("E-001", document(aggregateType), aggregateId, "SALE", true)
        val projection = projector.project(input, "TYPE_2")
        return String((projection as Projection.Built).bytes, Charsets.UTF_8)
    }

    private fun texts(
        xml: String,
        path: String,
    ): List<String> {
        val nodes = TableConformanceGate.select(TableConformanceGate.parse(xml), path, "380")
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    private val constructed =
        EndpointId
            .construct(true, SubmissionType.TYPE_2, "SG_UEN", "201888888A", null)
            .let { (it as EndpointIdResult.Built).value }

    @Test
    fun `INV-AC-26394 a POS aggregate gives IBT-049 POS`() {
        val xml = project("POS", "AGG-2026-0001")
        texts(xml, buyerPath) shouldBe listOf("POS")
        texts(xml, supplierPath) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26394 an STI aggregate gives IBT-049 STI`() {
        texts(project("STI", "AGG-2026-0001"), buyerPath) shouldBe listOf("STI")
    }

    @Test
    fun `a non-aggregate document keeps the EndpointId construct value`() {
        constructed shouldBe "SGUEN201888888A"
        texts(project(null, null), buyerPath) shouldBe listOf(constructed)
    }

    @Test
    fun `a PCP aggregate leaves IBT-049 to the construction`() {
        texts(project("PCP", "AGG-2026-0001"), buyerPath) shouldBe listOf(constructed)
    }

    @Test
    fun `the literal is not written to the supplier party`() {
        val xml = project("POS", "AGG-2026-0001")
        texts(xml, supplierPath) shouldBe emptyList()
        texts(xml, buyerPath).size shouldBe 1
    }
}
