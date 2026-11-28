package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.gst.payload.application.payload.RegenerationFacts
import com.dynokore.gst.payload.domain.ClassifiedFinding
import com.dynokore.gst.payload.domain.Severity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class BuildPayloadLayerOneTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val small = "<Invoice/>".toByteArray()
    private val big = ByteArray(10_485_761)

    private val ready = ArrayList<PayloadReadyFacts>()
    private val rejected = ArrayList<PayloadRejectedFacts>()
    private val calls = ArrayList<Pair<ByteArray, String>>()

    private val fatal = ClassifiedFinding("R-1", Severity.BLOCK, "PAY-002")
    private val escalated = ClassifiedFinding("R-2", Severity.BLOCK, "PAY-001")
    private val warning = ClassifiedFinding("R-3", Severity.WARN, null)
    private val decision =
        RejectionDecision("PAY-002", "R-1", listOf(DecisionPackVersion("SCH", "1")), emptyList())
    private val packs = listOf(PackVersionFact("SCHEMATRON", "S-1"))

    private fun build(
        projection: Projection = Projection.Built(small),
        result: LayerOneResult,
    ) = BuildPayload(
        inputs = { _, _ ->
            BuildInput("ENT-1", "{}", "AGG-1", null, null, transactionDate = LocalDate.parse("2000-02-01"))
        },
        decisions = { _, _ -> "TYPE_2" },
        projector = { _, _ -> projection },
        families = { "PINT_SG" },
        ready = PublishPayloadReady { ready += it },
        rejected = { rejected += it },
        outcomes = { _, _ -> true },
        layerOne =
            LayerOneCheck { bytes, family ->
                calls += bytes to family
                result
            },
        releases = acceptingSelector(),
    )

    private fun result(vararg f: ClassifiedFinding) = LayerOneResult(f.toList(), listOf(decision), packs)

    @Test
    fun `INV-AC-27185 a fatal finding stages PAY-002 with the decisions and the pack versions of the check`() {
        build(result = result(fatal, warning)).run(doc, 1)
        val r = rejected.single()
        r.code shouldBe "PAY-002"
        r.validationTrace shouldBe null
        r.decisions shouldBe listOf(decision)
        r.packVersions shouldBe packs
    }

    @Test
    fun `INV-AC-27185 an escalated warning alone stages PAY-001`() {
        build(result = result(escalated, warning)).run(doc, 1)
        rejected.single().code shouldBe "PAY-001"
    }

    @Test
    fun `INV-AC-27185 a fatal finding and an escalated warning stage PAY-002`() {
        build(result = result(escalated, fatal)).run(doc, 1)
        rejected.single().code shouldBe "PAY-002"
    }

    @Test
    fun `INV-AC-27185 a warning-only result publishes PayloadReady`() {
        build(result = result(warning)).run(doc, 1)
        ready.size shouldBe 1
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-27185 an empty result publishes PayloadReady`() {
        build(result = LayerOneResult(emptyList(), emptyList(), emptyList())).run(doc, 1)
        ready.size shouldBe 1
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-27185 the check receives the built bytes and the specification family of the request`() {
        build(result = result()).run(doc, 1)
        calls.size shouldBe 1
        calls.single().first.contentEquals(small) shouldBe true
        calls.single().second shouldBe "PINT_SG"
        ready.single().specificationVersion shouldBe "PINT_SG"
    }

    @Test
    fun `INV-AC-27185 a blocked projection never calls the check`() {
        build(projection = Projection.Blocked("{}"), result = result(fatal)).run(doc, 1)
        calls.size shouldBe 0
        rejected.single().validationTrace shouldBe "{}"
    }

    @Test
    fun `INV-AC-27185 a payload over the size limit stages PAY-010 and never calls the check`() {
        build(projection = Projection.Built(big), result = result(fatal)).run(doc, 1)
        calls.size shouldBe 0
        rejected.single().code shouldBe "PAY-010"
    }

    @Test
    fun `INV-AC-27185 a blocked verdict publishes no PayloadReady`() {
        build(result = result(fatal)).run(doc, 1)
        ready.size shouldBe 0
    }

    private val mde = PackVersionFact("MDE", "MDE-7")
    private val schematron = PackVersionFact("schematron", "SCH-4")

    private fun regenerationOf(
        mdeVersion: String?,
        vararg layerOnePacks: PackVersionFact,
    ): RegenerationFacts? {
        build(
            projection = Projection.Built(small, mdeVersion),
            result = LayerOneResult(emptyList(), emptyList(), layerOnePacks.toList()),
        ).run(doc, 3)
        rejected.size shouldBe 0
        return ready.single().regeneration
    }

    @Test
    fun `INV-AC-29337 the regeneration key holds the MDE version and then the Schematron version`() {
        regenerationOf("MDE-7", schematron) shouldBe RegenerationFacts(3, "SYNTH-A", listOf(mde, schematron))
    }

    @Test
    fun `INV-AC-29337 a Schematron version without an MDE version is recorded alone`() {
        regenerationOf(null, schematron) shouldBe RegenerationFacts(3, "SYNTH-A", listOf(schematron))
    }

    @Test
    fun `INV-AC-29337 an MDE version without a Schematron version is recorded alone`() {
        regenerationOf("MDE-7") shouldBe RegenerationFacts(3, "SYNTH-A", listOf(mde))
    }

    @Test
    fun `INV-AC-29337 with neither version there is no regeneration key`() {
        regenerationOf(null) shouldBe null
    }

    @Test
    fun `INV-AC-29337 the severity-override item beside the Schematron item is not in the key`() {
        val override = PackVersionFact("severity-override", "OVR-2")
        regenerationOf("MDE-7", schematron, override) shouldBe
            RegenerationFacts(3, "SYNTH-A", listOf(mde, schematron))
    }

    @Test
    fun `INV-AC-29337 two Schematron items throw and nothing is staged`() {
        val two = listOf(schematron, PackVersionFact("schematron", "SCH-5"))
        assertThrows(IllegalStateException::class.java) {
            build(result = LayerOneResult(emptyList(), emptyList(), two)).run(doc, 1)
        }
        ready.size shouldBe 0
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-29337 the look-alikes Schematron and schematron-rules are not recorded`() {
        val lookAlikes =
            arrayOf(PackVersionFact("Schematron", "SCH-8"), PackVersionFact("schematron-rules", "SCH-9"))
        regenerationOf("MDE-7", *lookAlikes) shouldBe RegenerationFacts(3, "SYNTH-A", listOf(mde))
    }

    @Test
    fun `INV-AC-29337 a blocked layer-one verdict keeps its rejection and publishes nothing`() {
        val checked = listOf(schematron, PackVersionFact("severity-override", "OVR-2"))
        build(
            projection = Projection.Built(small, "MDE-7"),
            result = LayerOneResult(listOf(fatal), listOf(decision), checked),
        ).run(doc, 1)
        rejected.single() shouldBe
            PayloadRejectedFacts("ENT-1", doc, 1, "PAY-002", null, checked, listOf(decision))
        ready.size shouldBe 0
    }
}
