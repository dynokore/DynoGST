package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonIdentityTripleCorrectedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqIdentityTripleCorrectionStore
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedDataReader
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedHandler
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
 * The configuration of the consumer of `IdentityTripleCorrected` (INV-REQ-1006, INV-AC-26837): the Jackson reader,
 * the jOOQ correction store, the handler and one `JdbcInboxConsumer` the event loop polls. The consumer refuses to be
 * built when the catalogue does not list the event on exactly this topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class IdentityTripleCorrectedConsumerConfiguration {
    @Bean
    fun identityTripleCorrectedDataReader(): IdentityTripleCorrectedDataReader =
        JacksonIdentityTripleCorrectedDataReader()

    @Bean
    fun identityTripleCorrectionStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqIdentityTripleCorrectionStore = JooqIdentityTripleCorrectionStore(dsl, runner)

    @Bean
    fun identityTripleCorrectedHandler(
        tenants: TenantContext,
        reader: IdentityTripleCorrectedDataReader,
        store: JooqIdentityTripleCorrectionStore,
    ): IdentityTripleCorrectedHandler = IdentityTripleCorrectedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList", "MemberNameEqualsClassName") // the bean name is fixed by INV-AC-26837
    fun identityTripleCorrectedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("identityTripleCorrectedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = IdentityTripleCorrectedConsumer.subscription()
        val entry = catalogue.find(SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TYPE)
        check(entry != null && entry.topic == SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TOPIC) {
            "the event catalogue does not list ${SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TYPE} on " +
                SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TOPIC
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
}

/**
 * The subscription of the consumer. A plain object, not a Spring component: a scanned configuration class of this
 * name would take the bean name `identityTripleCorrectedConsumer` of the inbox consumer bean (a bean name clash).
 */
object IdentityTripleCorrectedConsumer {
    private const val CONSUMER = "submission-svc"
    private const val SCHEMA = "submission"

    /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
    fun subscription(): ConsumerSubscription =
        ConsumerSubscription(CONSUMER, SubmissionEvents.IDENTITY_TRIPLE_CORRECTED_TOPIC, SCHEMA)
}
