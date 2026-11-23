package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.AccessAlertRaisedHandler
import com.dynokore.gst.notification.application.alerting.AccessAlertStore
import com.dynokore.gst.notification.application.alerting.HandleAccessAlertRaised
import com.dynokore.gst.notification.application.alerting.RoleHolders
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
 * The subscription of notification-svc to `AccessAlertRaised` (INV-REQ-895), as one inbox consumer of its own. The
 * handler runs in the consumer's transaction, so the alert, its recipients, the inbox row and the offset commit
 * together. The `RebuildRequiredSink` fallback is the one of [BacklogConsumerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class AccessAlertConsumerConfiguration {
    @Bean
    fun handleAccessAlertRaised(
        roles: RoleHolders,
        store: AccessAlertStore,
        clock: Clock,
    ): HandleAccessAlertRaised = HandleAccessAlertRaised(roles, store, clock)

    @Bean
    fun accessAlertRaisedHandler(
        useCase: HandleAccessAlertRaised,
        mapper: ObjectMapper,
    ): AccessAlertRaisedHandler = AccessAlertRaisedHandler(useCase, mapper)

    @Bean
    @Suppress("LongParameterList")
    fun accessAlertConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("accessAlertRaisedHandler") handler: EventHandler,
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
        const val TOPIC = AccessAlertRaisedHandler.TOPIC
        const val SCHEMA = "notification"
    }
}
