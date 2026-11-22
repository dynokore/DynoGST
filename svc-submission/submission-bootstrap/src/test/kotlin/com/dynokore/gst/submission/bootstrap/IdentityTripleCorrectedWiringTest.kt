package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqIdentityTripleCorrectionStore
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedHandler
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrections
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.tracing.TracedEventHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock

/**
 * INV-AC-26837 (INV-REQ-1006): the service built as TPL-18 writes it, over a lazy pool (no database), lists
 * `IdentityTripleCorrected` as a consumed event and declares a subscription, a handler and one inbox consumer for it.
 */
class IdentityTripleCorrectedWiringTest {
    private val topic = "gst.masterdata.identity-triple-corrected.v1"
    private val type = "com.dynokore.gst.masterdata.IdentityTripleCorrected.v1"
    private val config = IdentityTripleCorrectedConsumerConfiguration()

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
        config.identityTripleCorrectedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("identityTripleCorrectedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun without(context: ApplicationContext): List<CatalogueEntry> =
        context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    private fun held(
        consumer: JdbcInboxConsumer,
        field: String,
    ): Any =
        JdbcInboxConsumer::class.java
            .getDeclaredField(field)
            .also { it.isAccessible = true }
            .get(consumer)

    @Test
    fun `INV-AC-26837 the catalogue lists the event on its topic with producer masterdata-svc`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val entry = context.getBean(EventCatalogue::class.java).find(type)
        entry?.topic shouldBe topic
        entry?.producers shouldBe setOf("masterdata-svc")
        entry?.contentTopic shouldBe false
        SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TOPIC shouldBe topic
        SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TYPE shouldBe type
        SubmissionEvents.entries.map { it.eventType }.contains(type) shouldBe false
    }

    @Test
    fun `INV-AC-26837 one subscription and one inbox consumer exist for the topic`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val subscription = IdentityTripleCorrectedConsumer.subscription()
        subscription.topic shouldBe topic
        subscription.consumer shouldBe "submission-svc"
        subscription.schema shouldBe "submission"
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java)
        consumers.keys.filter { it.startsWith("identityTriple") } shouldBe listOf("identityTripleCorrectedConsumer")
        consumers.values.count { (held(it, "subscription") as ConsumerSubscription).topic == topic } shouldBe 1
        val handler = context.getBean("identityTripleCorrectedHandler")
        // the runtime wraps every EventHandler bean in a tracing decorator
        handler.shouldBeInstanceOf<TracedEventHandler>()
        handler.delegate.shouldBeInstanceOf<IdentityTripleCorrectedHandler>()
        context.getBean(JooqIdentityTripleCorrectionStore::class.java).shouldBeInstanceOf<IdentityTripleCorrections>()
    }

    @Test
    fun `INV-AC-26837 a catalogue listing the type on another topic or not at all makes the consumer refuse to build`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        consumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context))) }
        val lookAlike =
            CatalogueEntry(
                type,
                "gst.masterdata.identity-triple-corrected.v2",
                setOf("masterdata-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            )
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context) + lookAlike)) }
    }
}
