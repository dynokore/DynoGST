package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.index.JacksonCanonicalVersionCreatedReader
import com.dynokore.gst.consoleBff.adapters.index.JacksonMasterDataResolvedUenReader
import com.dynokore.gst.consoleBff.adapters.index.JacksonSubmissionAcknowledgedReader
import com.dynokore.gst.consoleBff.adapters.index.JacksonSubmissionDespatchedReader
import com.dynokore.gst.consoleBff.adapters.index.JacksonSubmissionFailedIndexReader
import com.dynokore.gst.consoleBff.adapters.index.JooqDocumentIndexReads
import com.dynokore.gst.consoleBff.adapters.index.JooqDocumentIndexStore
import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldReader
import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedReader
import com.dynokore.gst.consoleBff.application.index.CanonicalVersionCreatedHandler
import com.dynokore.gst.consoleBff.application.index.CanonicalVersionCreatedReader
import com.dynokore.gst.consoleBff.application.index.DocumentIndexReads
import com.dynokore.gst.consoleBff.application.index.DocumentIndexStore
import com.dynokore.gst.consoleBff.application.index.IndexDocumentHeldHandler
import com.dynokore.gst.consoleBff.application.index.IndexDocumentReleasedHandler
import com.dynokore.gst.consoleBff.application.index.IndexMasterDataResolvedHandler
import com.dynokore.gst.consoleBff.application.index.IndexMetrics
import com.dynokore.gst.consoleBff.application.index.IndexSubmissionAcknowledgedHandler
import com.dynokore.gst.consoleBff.application.index.IndexSubmissionDespatchedHandler
import com.dynokore.gst.consoleBff.application.index.IndexSubmissionFailedHandler
import com.dynokore.gst.consoleBff.application.index.MasterDataResolvedUenReader
import com.dynokore.gst.consoleBff.application.index.SubmissionAcknowledgedReader
import com.dynokore.gst.consoleBff.application.index.SubmissionDespatchedReader
import com.dynokore.gst.consoleBff.application.index.SubmissionFailedIndexReader
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The seven inbox consumers of the document index (INV-REQ-1037, INV-AC-26810): one `JdbcInboxConsumer` per topic over
 * the index handlers and store, in schema `console_bff`, under [INDEX_CONSUMER], a name distinct from the consumer name
 * of the exception consumers, so each topic keeps one offset row per consumer and the exception queue still receives
 * its events. The replay delivery of the index is its own bean with its own dispatch (one per consumer). The class
 * has one bean per element of the composition root (seven of each kind), hence the suppression.
 */
@Suppress("TooManyFunctions")
@Configuration(proxyBeanMethods = false)
class DocumentIndexConsumerConfiguration {
    @Bean
    fun indexCanonicalVersionSubscription(): ConsumerSubscription =
        subscription(ConsoleEvents.CANONICAL_VERSION_CREATED_TOPIC)

    @Bean
    fun indexDocumentHeldSubscription(): ConsumerSubscription = subscription(ConsoleEvents.DOCUMENT_HELD_TOPIC)

    @Bean
    fun indexDocumentReleasedSubscription(): ConsumerSubscription = subscription(ConsoleEvents.DOCUMENT_RELEASED_TOPIC)

    @Bean
    fun indexSubmissionDespatchedSubscription(): ConsumerSubscription =
        subscription(ConsoleEvents.SUBMISSION_DESPATCHED_TOPIC)

    @Bean
    fun indexSubmissionAcknowledgedSubscription(): ConsumerSubscription =
        subscription(ConsoleEvents.SUBMISSION_ACKNOWLEDGED_TOPIC)

    @Bean
    fun indexSubmissionFailedSubscription(): ConsumerSubscription = subscription(ConsoleEvents.SUBMISSION_FAILED_TOPIC)

    @Bean
    fun indexMasterDataResolvedSubscription(): ConsumerSubscription =
        subscription(ConsoleEvents.MASTER_DATA_RESOLVED_TOPIC)

    @Bean
    fun documentIndexStore(dsl: DSLContext): DocumentIndexStore = JooqDocumentIndexStore(dsl)

    @Bean
    fun documentIndexReads(dsl: DSLContext): DocumentIndexReads = JooqDocumentIndexReads(dsl)

    @Bean
    fun canonicalVersionCreatedReader(): CanonicalVersionCreatedReader = JacksonCanonicalVersionCreatedReader()

    @Bean
    fun submissionDespatchedReader(): SubmissionDespatchedReader = JacksonSubmissionDespatchedReader()

    @Bean
    fun submissionAcknowledgedReader(): SubmissionAcknowledgedReader = JacksonSubmissionAcknowledgedReader()

    @Bean
    fun submissionFailedIndexReader(): SubmissionFailedIndexReader = JacksonSubmissionFailedIndexReader()

    @Bean
    fun masterDataResolvedUenReader(): MasterDataResolvedUenReader = JacksonMasterDataResolvedUenReader()

    @Bean
    fun indexMetrics(registry: MeterRegistry): IndexMetrics {
        val counter = registry.counter(UNSCOPED_VERSION_EVENTS_METRIC)
        return object : IndexMetrics {
            override fun unscopedVersionEvent() = counter.increment()
        }
    }

    @Bean
    fun indexCanonicalVersionHandler(
        tenants: TenantContext,
        reader: CanonicalVersionCreatedReader,
        store: DocumentIndexStore,
        metrics: IndexMetrics,
    ): CanonicalVersionCreatedHandler = CanonicalVersionCreatedHandler(tenants, reader, store, metrics)

    @Bean
    fun indexDocumentHeldHandler(
        tenants: TenantContext,
        reader: DocumentHeldReader,
        store: DocumentIndexStore,
    ): IndexDocumentHeldHandler = IndexDocumentHeldHandler(tenants, reader, store)

    @Bean
    fun indexDocumentReleasedHandler(
        tenants: TenantContext,
        reader: DocumentReleasedReader,
        store: DocumentIndexStore,
    ): IndexDocumentReleasedHandler = IndexDocumentReleasedHandler(tenants, reader, store)

    @Bean
    fun indexSubmissionDespatchedHandler(
        tenants: TenantContext,
        reader: SubmissionDespatchedReader,
        store: DocumentIndexStore,
    ): IndexSubmissionDespatchedHandler = IndexSubmissionDespatchedHandler(tenants, reader, store)

    @Bean
    fun indexSubmissionAcknowledgedHandler(
        tenants: TenantContext,
        reader: SubmissionAcknowledgedReader,
        store: DocumentIndexStore,
    ): IndexSubmissionAcknowledgedHandler = IndexSubmissionAcknowledgedHandler(tenants, reader, store)

    @Bean
    fun indexSubmissionFailedHandler(
        tenants: TenantContext,
        reader: SubmissionFailedIndexReader,
        store: DocumentIndexStore,
    ): IndexSubmissionFailedHandler = IndexSubmissionFailedHandler(tenants, reader, store)

    @Bean
    fun indexMasterDataResolvedHandler(
        tenants: TenantContext,
        reader: MasterDataResolvedUenReader,
        store: DocumentIndexStore,
    ): IndexMasterDataResolvedHandler = IndexMasterDataResolvedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun indexCanonicalVersionConsumer(
        @Qualifier("indexCanonicalVersionSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexCanonicalVersionHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.CANONICAL_VERSION_CREATED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexDocumentHeldConsumer(
        @Qualifier("indexDocumentHeldSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexDocumentHeldHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.DOCUMENT_HELD_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexDocumentReleasedConsumer(
        @Qualifier("indexDocumentReleasedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexDocumentReleasedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.DOCUMENT_RELEASED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexSubmissionDespatchedConsumer(
        @Qualifier("indexSubmissionDespatchedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexSubmissionDespatchedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.SUBMISSION_DESPATCHED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexSubmissionAcknowledgedConsumer(
        @Qualifier("indexSubmissionAcknowledgedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexSubmissionAcknowledgedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.SUBMISSION_ACKNOWLEDGED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexSubmissionFailedConsumer(
        @Qualifier("indexSubmissionFailedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexSubmissionFailedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.SUBMISSION_FAILED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun indexMasterDataResolvedConsumer(
        @Qualifier("indexMasterDataResolvedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("indexMasterDataResolvedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.MASTER_DATA_RESOLVED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    /**
     * The one replay delivery of the index consumers: its inbox (not a bean, so it is not polled twice) has the index
     * consumer name and dispatches each of the seven types to its index handler, nothing else. It claims from its own
     * queue named [INDEX_CONSUMER], so it never takes the items of the exception consumers.
     */
    @Bean
    @Suppress("LongParameterList")
    fun documentIndexReplayDelivery(
        limiter: LaneRateLimiter,
        scope: TenantScope,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        @Qualifier("indexCanonicalVersionHandler") version: EventHandler,
        @Qualifier("indexDocumentHeldHandler") held: EventHandler,
        @Qualifier("indexDocumentReleasedHandler") released: EventHandler,
        @Qualifier("indexSubmissionDespatchedHandler") despatched: EventHandler,
        @Qualifier("indexSubmissionAcknowledgedHandler") acknowledged: EventHandler,
        @Qualifier("indexSubmissionFailedHandler") failed: EventHandler,
        @Qualifier("indexMasterDataResolvedHandler") masterData: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): ReplayDelivery {
        val dispatch =
            TypeDispatchHandler(
                mapOf(
                    ConsoleEvents.CANONICAL_VERSION_CREATED_TYPE to version,
                    ConsoleEvents.DOCUMENT_HELD_TYPE to held,
                    ConsoleEvents.DOCUMENT_RELEASED_TYPE to released,
                    ConsoleEvents.SUBMISSION_DESPATCHED_TYPE to despatched,
                    ConsoleEvents.SUBMISSION_ACKNOWLEDGED_TYPE to acknowledged,
                    ConsoleEvents.SUBMISSION_FAILED_TYPE to failed,
                    ConsoleEvents.MASTER_DATA_RESOLVED_TYPE to masterData,
                ),
            )
        val dsl = TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions)
        val inbox =
            JdbcInboxConsumer(
                subscription(ConsoleEvents.CANONICAL_VERSION_CREATED_TOPIC),
                catalogue,
                reader,
                scope,
                dsl,
                dispatch,
                rebuildRequired,
                clock,
            )
        // not a bean: a WorkQueue bean would replace the service default and make `queue: WorkQueue` ambiguous
        val queue = JdbcWorkQueue(scope, dsl, SCHEMA, limiter, clock, INDEX_CONSUMER)
        return JdbcReplayDelivery(queue, scope, inbox)
    }

    companion object {
        const val INDEX_CONSUMER = "console-bff-index"
        const val SCHEMA = ExceptionConsumerConfiguration.SCHEMA
        const val UNSCOPED_VERSION_EVENTS_METRIC = "console_bff_index_unscoped_version_events_total"

        private fun subscription(topic: String) = ConsumerSubscription(INDEX_CONSUMER, topic, SCHEMA)
    }
}
