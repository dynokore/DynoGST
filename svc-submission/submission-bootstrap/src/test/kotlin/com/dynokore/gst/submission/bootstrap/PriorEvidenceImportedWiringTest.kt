package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.arrival.ArrivingDocumentFacts
import com.dynokore.gst.submission.application.arrival.PriorSolutionCheck
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock

/**
 * INV-AC-28449: the service built as TPL-18 writes it, over a lazy pool (no database), wires the prior-solution check
 * into the `PayloadReady` handler and consumes `PriorSubmissionEvidenceImported`, which feeds the check.
 */
class PriorEvidenceImportedWiringTest {
    private val topic = "gst.evidence.prior-submission-evidence-imported.v1"
    private val type = "com.dynokore.gst.evidence.PriorSubmissionEvidenceImported.v1"
    private val config = PriorEvidenceImportedConsumer()

    private fun withContext(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        SubmissionContext.offline(dir).run { block(it) }
    }

    private fun consumer(
        context: ApplicationContext,
        catalogue: EventCatalogue,
    ): JdbcInboxConsumer =
        config.priorEvidenceImportedInboxConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("priorEvidenceImportedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun without(context: ApplicationContext): List<CatalogueEntry> =
        context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    @Test
    fun `INV-AC-28449 the event constants equal the catalogue strings and the producer is evidence-svc`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TOPIC shouldBe topic
        SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TYPE shouldBe type
        val entry = context.getBean(EventCatalogue::class.java).find(type)
        entry?.topic shouldBe topic
        entry?.producers shouldBe setOf("evidence-svc")
        entry?.contentTopic shouldBe false
        entry?.recordTopic shouldBe false
        entry?.retainedWhileResolvable shouldBe false
        SubmissionEvents.consumed.map { it.eventType }.contains(type) shouldBe true
        SubmissionEvents.entries.map { it.eventType }.contains(type) shouldBe false
    }

    @Test
    fun `INV-AC-28449 the check, the handler and one inbox consumer for the topic are wired`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        context.getBeansOfType(ArrivingDocumentFacts::class.java).keys shouldBe setOf("arrivingDocumentFacts")
        context.getBean(PriorSolutionCheck::class.java).shouldBeInstanceOf<PriorSolutionCheck>()
        context.getBean("payloadReadyHandler").shouldBeInstanceOf<EventHandler>()
        context.getBean("priorEvidenceImportedHandler").shouldBeInstanceOf<EventHandler>() // traced by service-runtime
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java)
        consumers.keys.filter { it.startsWith("priorEvidence") } shouldBe listOf("priorEvidenceImportedInboxConsumer")
        val held = JdbcInboxConsumer::class.java.getDeclaredField("subscription").also { it.isAccessible = true }
        val subscriptions = consumers.mapValues { held.get(it.value) as ConsumerSubscription }
        subscriptions.getValue("priorEvidenceImportedInboxConsumer").topic shouldBe topic
        subscriptions.values.count { it.topic == topic } shouldBe 1
        PriorEvidenceImportedConsumer.subscription().consumer shouldBe "submission-svc"
        PriorEvidenceImportedConsumer.subscription().schema shouldBe "submission"
    }

    @Test
    fun `INV-AC-28449 a catalogue listing the event on another topic or not at all makes the consumer refuse`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        consumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context))) }
        val lookAlike =
            CatalogueEntry(
                type,
                "gst.evidence.prior-submission-evidence-imported.v2",
                setOf("evidence-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            )
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context) + lookAlike)) }
    }
}
