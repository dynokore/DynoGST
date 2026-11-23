package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.IdentityTripleReprocessingRequestedReader
import com.dynokore.gst.canonical.adapters.events.JacksonIdentityTripleDocuments
import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqCurrentDocumentVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqIdentityTripleRequestMarkers
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.reprocessing.CurrentDocumentVersions
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleDocuments
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleReprocessing
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleRequestMarkers
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * INV-REQ-1006 (INV-T-26793): the subscription of canonical-svc to `IdentityTripleReprocessingRequested` as one inbox
 * consumer on the inbox, offset and dead-letter tables of schema `canonical`. The handler of slice 1 runs in the
 * tenant of the CloudEvents envelope with the CloudEvents id as event id; its retry outcomes are exceptions, so the
 * delivery is retried, never dropped. The consumer is built only when the event catalogue lists the event type on
 * its topic: otherwise the context fails to start (fail closed). The service identity the page is written as is the
 * workload principal of the tenant scope, never a role (INV-Q-10647).
 */
@Configuration(proxyBeanMethods = false)
class IdentityTripleReprocessingConsumerConfiguration {
    @Bean
    fun currentDocumentVersions(
        dsl: DSLContext,
        tenants: TenantContext,
    ): CurrentDocumentVersions = JooqCurrentDocumentVersions(dsl, tenants)

    @Bean
    fun identityTripleDocuments(): IdentityTripleDocuments = JacksonIdentityTripleDocuments()

    @Bean
    fun identityTripleRequestMarkers(
        dsl: DSLContext,
        clock: Clock,
    ): IdentityTripleRequestMarkers = JooqIdentityTripleRequestMarkers(dsl, clock)

    @Bean
    @Suppress("LongParameterList")
    fun identityTripleReprocessing(
        transaction: TransactionRunner,
        markers: IdentityTripleRequestMarkers,
        current: CurrentDocumentVersions,
        documents: IdentityTripleDocuments,
        supplier: AggregateSupplierIdentity,
        store: DocumentStore,
        publisher: OutboxCanonicalVersionCreatedPublisher,
        scope: TenantScope,
    ): IdentityTripleReprocessing =
        IdentityTripleReprocessing(
            transaction,
            markers,
            current,
            documents,
            supplier,
            CreateDocumentVersion(store, transaction),
            publisher,
            scope.workloadPrincipal,
        ) { LOG.info(it) }

    @Bean
    fun identityTripleReprocessingRequestedHandler(
        handler: IdentityTripleReprocessing,
        trace: InboundEventTrace,
    ): EventHandler =
        InboundTraceHandler(
            IdentityTripleReprocessingRequestedEventHandler(IdentityTripleReprocessingRequestedReader(), handler),
            trace,
        )

    @Bean
    @Suppress("LongParameterList")
    fun identityTripleReprocessingConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        identityTripleReprocessingRequestedHandler: EventHandler,
    ): JdbcInboxConsumer {
        val entry = catalogue.find(CanonicalEvents.IDENTITY_TRIPLE_REPROCESSING_REQUESTED)
        check(entry != null && entry.topic == TOPIC) {
            "the event catalogue does not list ${CanonicalEvents.IDENTITY_TRIPLE_REPROCESSING_REQUESTED} on $TOPIC"
        }
        return JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            identityTripleReprocessingRequestedHandler,
            rebuildRequired,
            clock,
        )
    }

    companion object {
        const val CONSUMER = "canonical-svc-identity-triple-reprocessing-requested"
        const val TOPIC = CanonicalEvents.IDENTITY_TRIPLE_REPROCESSING_TOPIC
        const val SCHEMA = "canonical"
        private val LOG = LoggerFactory.getLogger(IdentityTripleReprocessingConsumerConfiguration::class.java)
    }
}

/**
 * Reads one `IdentityTripleReprocessingRequested` event and hands it to the slice 1 handler in the tenant of the
 * envelope, with the CloudEvents id as the event id. A malformed payload is a [ContractViolation] (dead letter at
 * once); any other failure of the handler propagates, so the delivery is retried.
 */
class IdentityTripleReprocessingRequestedEventHandler(
    private val reader: IdentityTripleReprocessingRequestedReader,
    private val handler: IdentityTripleReprocessing,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val request = reader.read(event.dataJson)
        handler.handle(event.tenant.value, request.entityRef, request.docUuids, event.id.value)
        return HandleOutcome.APPLIED
    }
}
