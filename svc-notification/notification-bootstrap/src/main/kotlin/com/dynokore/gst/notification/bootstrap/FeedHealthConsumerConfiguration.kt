package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.FeedHealthChangedHandler
import com.dynokore.gst.notification.adapters.persistence.AuthorityReplicaRoleHolders
import com.dynokore.gst.notification.application.alerting.FeedAlertStore
import com.dynokore.gst.notification.application.alerting.FeedHealthStates
import com.dynokore.gst.notification.application.alerting.HandleFeedHealthChanged
import com.dynokore.gst.notification.application.alerting.ResolveDefaultRecipients
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of notification-svc to `FeedHealthChanged` (INV-REQ-910, INV-AC-1944), as one inbox consumer of its
 * own. The consumer name is shared with the other consumers of the service: the offset key is (consumer, topic) and
 * the inbox key is (consumer, event_id), so one name on several topics is accepted. The handler runs in the consumer's
 * transaction, so the alert, the feed health state, the inbox row and the offset commit together (INV-REQ-1222).
 */
@Configuration(proxyBeanMethods = false)
class FeedHealthConsumerConfiguration {
    @Bean
    fun handleFeedHealthChanged(
        states: FeedHealthStates,
        alerts: FeedAlertStore,
        holders: AuthorityReplicaRoleHolders,
        clock: Clock,
    ): HandleFeedHealthChanged = HandleFeedHealthChanged(states, alerts, ResolveDefaultRecipients(holders), clock)

    @Bean
    fun feedHealthChangedHandler(
        useCase: HandleFeedHealthChanged,
        mapper: ObjectMapper,
    ): FeedHealthChangedHandler = FeedHealthChangedHandler(useCase, mapper)

    @Bean
    @Suppress("LongParameterList")
    fun feedHealthConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("feedHealthChangedHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            handler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "notification-svc"
        const val TOPIC = FeedHealthChangedHandler.TOPIC
        const val SCHEMA = "notification"
    }
}
