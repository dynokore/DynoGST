package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPriorEvidenceImportedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqImportedPriorEvidenceStore
import com.dynokore.gst.submission.application.migration.ImportedPriorEvidenceStore
import com.dynokore.gst.submission.application.migration.PriorEvidenceImportedDataReader
import com.dynokore.gst.submission.application.migration.PriorEvidenceImportedHandler
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The consumer of `PriorSubmissionEvidenceImported` (INV-REQ-1109): the Jackson reader, the jOOQ evidence store, the
 * handler and one `JdbcInboxConsumer` the event loop polls, the way [EntityProfileChangedConsumer] declares its own.
 * The consumer refuses to be built when the catalogue does not list the event on exactly this topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class PriorEvidenceImportedConsumer {
    @Bean
    fun priorEvidenceImportedDataReader(): PriorEvidenceImportedDataReader = JacksonPriorEvidenceImportedDataReader()

    @Bean
    fun importedPriorEvidenceStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ImportedPriorEvidenceStore = JooqImportedPriorEvidenceStore(dsl, runner)

    @Bean
    fun priorEvidenceImportedHandler(
        tenants: TenantContext,
        reader: PriorEvidenceImportedDataReader,
        store: ImportedPriorEvidenceStore,
    ): PriorEvidenceImportedHandler = PriorEvidenceImportedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun priorEvidenceImportedInboxConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("priorEvidenceImportedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TYPE)
        check(entry != null && entry.topic == SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TOPIC) {
            "the event catalogue does not list ${SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TYPE} on " +
                SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TOPIC
        }
        return JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )
    }

    companion object {
        private const val CONSUMER = "submission-svc"
        private const val SCHEMA = "submission"

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.PRIOR_SUBMISSION_EVIDENCE_IMPORTED_TOPIC, SCHEMA)
    }
}
