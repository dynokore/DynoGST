package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.Projection
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** INV-AC-26578: a projection names the version of the active MDE pack it was built with; no database. */
class PintSgPayloadProjectorPackVersionTest {
    private fun input(typeCode: String) =
        BuildInput(
            "E-001",
            """
            {"documentTypeCode":{"value":"$typeCode","classification":"NONE"},
             "parties":{"value":{
               "supplier":{"identifierScheme":"SG_UEN","identifierValue":"201912345A","name":"Acme"},
               "buyer":{"identifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Buyer"}},"classification":"IDENTIFYING"},
             "documentNumber":{"value":"INV-2026-000123","classification":"NONE"},
             "documentDate":{"value":"2026-03-20","classification":"NONE"},
             "invoiceNote":{"value":"Thanks","classification":"NONE"},
             "identityTriple":{"value":{"senderId":"0195:201912345A","supplierId":"201912345A","supplierGstn":"M2-1234567-8"},
               "classification":"IDENTIFYING"}}
            """.trimIndent(),
            null,
            "SALE",
            true,
        )

    private val projector =
        PintSgPayloadProjector({ "PINT_SG" }, { ActivePackRead("TEST", MdePackOffNetworkPrefix("{}"), "{}") })

    @Test
    fun `INV-AC-26578 a built projection names the pack version`() {
        val built = projector.project(input("380"), "TYPE_2").shouldBeInstanceOf<Projection.Built>()
        built.packVersion shouldBe "TEST"
    }

    @Test
    fun `INV-AC-26578 a projection blocked after the pack read still names the pack version`() {
        val blocked = projector.project(input("999"), "TYPE_2").shouldBeInstanceOf<Projection.Blocked>()
        blocked.packVersion shouldBe "TEST"
    }
}
