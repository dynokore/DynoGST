package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.BoundMessageChannels
import com.dynokore.gst.notification.adapters.delivery.ClosedTemplateVersions
import com.dynokore.gst.notification.adapters.events.MessageRequestedHandler
import com.dynokore.gst.notification.adapters.persistence.JooqMessageDeliveryRecords
import com.dynokore.gst.notification.application.delivery.MessageChannels
import com.dynokore.gst.notification.application.delivery.MessageDeliveryRecords
import com.dynokore.gst.notification.application.delivery.PasscodeOpener
import com.dynokore.gst.notification.application.delivery.TemplateVersions
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The subscription of notification-svc to `MessageRequested` (INV-REQ-817, INV-REQ-2762), as one inbox consumer of its
 * own next to the other consumers of the service. The handler runs in the consumer's transaction, so the delivery
 * rows, the inbox row and the offset commit together. No channel adapter ships here: with no `MessageDelivery` bean
 * bound, every record is FAILED CHANNEL_NOT_BOUND (fail closed). The passcode opener is the bean of
 * [PasscodeOpenerConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class MessageRequestedConsumerConfiguration {
    @Bean
    fun messageDeliveryRecords(
        dsl: DSLContext,
        tenants: TenantContext,
    ): MessageDeliveryRecords = JooqMessageDeliveryRecords(dsl, tenants)

    @Bean
    fun messageChannels(bound: ObjectProvider<MessageDelivery>): MessageChannels =
        BoundMessageChannels(bound.orderedStream().toList())

    @Bean
    fun templateVersions(): TemplateVersions = ClosedTemplateVersions()

    @Bean
    @Suppress("LongParameterList")
    fun messageRequestedHandler(
        records: MessageDeliveryRecords,
        channels: MessageChannels,
        templates: TemplateVersions,
        clock: Clock,
        mapper: ObjectMapper,
        passcodes: PasscodeOpener,
    ): MessageRequestedHandler = MessageRequestedHandler(records, channels, templates, clock, mapper, passcodes)

    @Bean
    @Suppress("LongParameterList")
    fun messageRequestedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("messageRequestedHandler") handler: EventHandler,
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
        const val TOPIC = MessageRequestedHandler.TOPIC
        const val SCHEMA = "notification"
    }
}
