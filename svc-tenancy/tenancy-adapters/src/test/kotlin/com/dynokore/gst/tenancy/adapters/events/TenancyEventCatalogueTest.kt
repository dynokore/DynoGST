package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.productruntime.ProductEventTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** The emission guard accepts what tenancy-svc produces and rejects an uncatalogued type (TPL-05, API-43). */
class TenancyEventCatalogueTest {
    private val catalogue = TenancyEventCatalogue.load()
    private val guard = EmissionGuard(catalogue, TenancyEventCatalogue.SERVICE)

    @Test
    fun `the guard accepts the structure event and the types the contract catalogues for tenancy`() {
        guard.require(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
        guard.require("com.dynokore.gst.tenancy.EntityProfileChanged.v1")
    }

    @Test
    fun `the guard rejects an uncatalogued type, the placeholder and a look-alike`() {
        listOf(
            "com.dynokore.gst.tenancy.NotInTheCatalogue.v1",
            "com.dynokore.gst.tenancy.ExampleHappened.v1",
            "com.dynokore.gst.tenancy.organisationstructurechanged.v1",
            "",
        ).forEach { type -> shouldThrow<EmissionNotPermitted> { guard.require(type) } }
    }

    @Test
    fun `another service's guard over the same catalogue does not let it emit the structure event`() {
        shouldThrow<EmissionNotPermitted> {
            EmissionGuard(catalogue, "access-svc").require(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
        }
    }

    @Test
    fun `INV-AC-2779 the catalogue lists ShortCodeUsed as consumed from aggregation-svc by equality`() {
        catalogue.find("com.dynokore.gst.aggregation.ShortCodeUsed.v1") shouldBe
            CatalogueEntry(
                "com.dynokore.gst.aggregation.ShortCodeUsed.v1",
                "gst.aggregation.short-code-used.v1",
                setOf("aggregation-svc"),
                contentTopic = false,
            )
    }

    @Test
    fun `INV-AC-23029 the catalogue lists UnsubmittedDocumentCountMeasured from submission-svc by equality`() {
        catalogue.find("com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1") shouldBe
            CatalogueEntry(
                "com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1",
                "gst.submission.unsubmitted-document-count-measured.v1",
                setOf("submission-svc"),
                contentTopic = false,
            )
        catalogue.find("com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v2") shouldBe null
        catalogue.find("com.dynokore.gst.submission.UnsubmittedDocumentCountMeasure.v1") shouldBe null
    }

    @Test
    fun `INV-AC-16774 the catalogue lists SubmissionDespatched as consumed from submission-svc by equality`() {
        catalogue.find("com.dynokore.gst.submission.SubmissionDespatched.v1") shouldBe
            CatalogueEntry(
                "com.dynokore.gst.submission.SubmissionDespatched.v1",
                "gst.submission.submission-despatched.v1",
                setOf("submission-svc"),
                contentTopic = false,
            )
        catalogue.find("com.dynokore.gst.submission.SubmissionDespatched.v2") shouldBe null
        catalogue.find("com.dynokore.gst.submission.SubmissionDespatch.v1") shouldBe null
    }

    @Test
    fun `INV-AC-28054 the catalogue lists TriggerCoverageChanged as produced by the topic file producer`() {
        val topicProducers = producersInTopicFile(TenancyEventCatalogue.TRIGGER_COVERAGE_CHANGED_TOPIC)
        (topicProducers.isNotEmpty()) shouldBe true
        catalogue.find(TenancyEventCatalogue.TRIGGER_COVERAGE_CHANGED_TYPE)?.producers shouldBe topicProducers
        val source =
            File("src/main/kotlin/com/dynokore/gst/tenancy/adapters/events/TenancyEventCatalogue.kt").readText()
        source.contains("\"ingestion-svc\"") shouldBe false
    }

    @Test
    fun `INV-AC-28054 a topic absent from the file or without a producer fails naming the topic`() {
        val topic = TenancyEventCatalogue.TRIGGER_COVERAGE_CHANGED_TOPIC
        val other =
            "topics:\n  - topic: gst.other.thing.v1\n    event_type: x.Other.v1\n" +
                "    producers:\n      - other-svc\n"
        val absent = shouldThrow<IllegalArgumentException> { triggerCoverageProducers(other) }
        absent.message!!.contains(topic) shouldBe true
        val bare = "topics:\n  - topic: $topic\n    event_type: x.T.v1\n    producers:\n"
        val none = shouldThrow<IllegalArgumentException> { triggerCoverageProducers(bare) }
        none.message!!.contains(topic) shouldBe true
        triggerCoverageProducers(other.replace("gst.other.thing.v1", topic)) shouldBe setOf("other-svc")
    }

    private fun producersInTopicFile(topic: String): Set<String> {
        val lines =
            checkNotNull(javaClass.classLoader.getResourceAsStream(TenancyEventCatalogue.TOPICS_RESOURCE))
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .lines()
        val start = lines.indexOfFirst { it.contains("topic: $topic") }
        check(start >= 0) { "topic $topic is not in the topic file" }
        val block = lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("- topic:") }
        return block
            .dropWhile { !it.trimStart().startsWith("producers:") }
            .drop(1)
            .takeWhile { it.trimStart().startsWith("- ") }
            .map { it.trim().removePrefix("- ").trim() }
            .toSet()
    }
}
