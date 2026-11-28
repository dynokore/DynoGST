package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.ConfiguredAcceptanceCalendars
import com.dynokore.gst.payload.application.build.BuildInput
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.LayerOneCheck
import com.dynokore.gst.payload.application.build.LayerOneResult
import com.dynokore.gst.payload.application.build.PayloadRejectedFacts
import com.dynokore.gst.payload.application.build.Projection
import com.dynokore.gst.payload.application.build.SpecificationReleaseSelector
import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.gst.payload.domain.ClassifiedFinding
import com.dynokore.gst.payload.domain.Severity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.LocalDate
import java.util.UUID

class BuildPayloadConfigurationTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val small = "<Invoice/>".toByteArray()
    private val ready = ArrayList<PayloadReadyFacts>()
    private val rejected = ArrayList<PayloadRejectedFacts>()

    private fun build(factory: DefaultListableBeanFactory): BuildPayload =
        BuildPayloadConfiguration().buildPayload(
            inputs = { _, _ ->
                BuildInput("ENT-1", "{}", "AGG-1", null, null, transactionDate = LocalDate.of(2000, 2, 1))
            },
            decisions = { _, _ -> "TYPE_2" },
            projector = { _, _ -> Projection.Built(small) },
            families = { "PINT_SG" },
            ready = PublishPayloadReady { ready += it },
            rejected = { rejected += it },
            outcomes = { _, _ -> true },
            layerOne = factory.getBeanProvider(LayerOneCheck::class.java),
            releases =
                SpecificationReleaseSelector(ConfiguredAcceptanceCalendars.parse(PayloadContext.CALENDAR)) {
                    LocalDate.of(2000, 7, 15)
                },
        )

    @Test
    fun `INV-AC-27185 with no LayerOneCheck bean the build bean falls back to NOT_WIRED`() {
        build(DefaultListableBeanFactory()).run(doc, 1)
        ready.single().payloadBytes.contentEquals(small) shouldBe true
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-27185 with a LayerOneCheck bean the build bean calls that check`() {
        val seen = ArrayList<ByteArray>()
        val check =
            LayerOneCheck { bytes, _ ->
                seen += bytes
                LayerOneResult(listOf(ClassifiedFinding("R-1", Severity.BLOCK, "PAY-002")), emptyList(), emptyList())
            }
        val factory = DefaultListableBeanFactory()
        factory.registerSingleton("layerOneCheck", check)
        build(factory).run(doc, 1)
        seen.size shouldBe 1
        seen.single().contentEquals(small) shouldBe true
        rejected.single().code shouldBe "PAY-002"
        ready.size shouldBe 0
    }
}
