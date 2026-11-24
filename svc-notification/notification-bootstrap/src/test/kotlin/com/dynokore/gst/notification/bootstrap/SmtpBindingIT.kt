package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.FakeSmtpServer
import com.dynokore.gst.notification.adapters.delivery.SmtpDeliveryProperties
import com.dynokore.gst.notification.adapters.delivery.SmtpMessageDelivery
import com.dynokore.gst.notification.adapters.events.MessageRequestedHandler
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.MessageDelivery.Companion.MESSAGE_DELIVERY
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-26568: the EMAIL channel is bound only when every SMTP key is configured, over the Flyway-built schema with
 * the delivery chain wired as in production (the real bean of [MessageDeliveryConfiguration], the real
 * `MessageChannels`, the real handler) against an SMTP server double. The one substitution is the trust anchor: the
 * double's certificate is self-signed, so a post-processor rebuilds the bean with the double's socket factory from the
 * properties read back from the production bean (the adapter's own private-CA hook); everything else is the
 * production wiring.
 */
class SmtpBindingIT {
    private val actor = "svc:notification-svc"
    private val json = EnvelopeJson()
    private var sequence = 0L

    private class Trust(
        private val factory: javax.net.ssl.SSLSocketFactory,
    ) : BeanPostProcessor,
        BeanFactoryAware {
        private lateinit var beans: BeanFactory

        override fun setBeanFactory(beanFactory: BeanFactory) {
            beans = beanFactory
        }

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any =
            if (bean is SmtpMessageDelivery) {
                // the properties the production bean was built with, read back from it (never re-parsed here)
                val built =
                    SmtpMessageDelivery::class.java
                        .getDeclaredField("properties")
                        .also { it.isAccessible = true }
                        .get(bean) as SmtpDeliveryProperties
                SmtpMessageDelivery(
                    built,
                    beans.getBean(SecretStore::class.java),
                    trustedSocketFactory = factory,
                )
            } else {
                bean
            }
    }

    private class Log : EventLogReader {
        private val records = mutableListOf<com.dynokore.platform.outboxandqueue.LogRecord>()

        fun add(envelopeJson: String) {
            records +=
                com.dynokore.platform.outboxandqueue
                    .LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ) = records.filter { it.offset > afterOffset }.take(limit)
    }

    private val allKeys =
        mapOf(
            SmtpDeliveryProperties.PORT to "",
            SmtpDeliveryProperties.FROM_ADDRESS to "sender-9x7@relay-fail.test",
            SmtpDeliveryProperties.CREDENTIAL_SECRET_REF to "smtp/relay-ref-9x7",
            SmtpDeliveryProperties.CONNECT_TIMEOUT_MS to "2001",
            SmtpDeliveryProperties.READ_TIMEOUT_MS to "5002",
            SmtpDeliveryProperties.WRITE_TIMEOUT_MS to "5003",
            SmtpDeliveryProperties.STATUS_TABLE_SIZE to "17",
        )

    private fun runner(
        dir: Path,
        server: FakeSmtpServer,
        vararg without: String,
    ): ApplicationContextRunner {
        val ref = allKeys.getValue(SmtpDeliveryProperties.CREDENTIAL_SECRET_REF)
        Files.createDirectories(dir.resolve(ref).parent)
        Files.writeString(dir.resolve(ref), "relay-user\nrelay-password")
        val values =
            (allKeys + (SmtpDeliveryProperties.HOST to "localhost") + (SmtpDeliveryProperties.PORT to "${server.port}"))
                .filterKeys { it !in without }
                .map { "${it.key}=${it.value}" }
        return NotificationContext
            .online(dir)
            .withPropertyValues(*values.toTypedArray())
            .withBean(Trust::class.java, { Trust(server.clientSocketFactory) })
    }

    private fun envelope(
        tenant: TenantId,
        data: String,
    ): String =
        json.write(
            Envelope(
                id = EventId("ev-" + UUID.randomUUID()),
                source = "/gst/access-svc",
                type = MessageRequestedHandler.EVENT_TYPE,
                subject = "E-1",
                time = Instant.parse("2026-11-21T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "access-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
            ),
        )

    private val emailEvent =
        """{"template":"signed-link","recipients":["a@mail.test"],"channel":"EMAIL","linkReference":"link-ref-1"}"""
    private val smsEvent =
        """{"template":"signed-link-passcode","recipients":["+6591234567"],"channel":"SMS",""" +
            """"sealedPasscode":"SEALED-9f3a7c21"}"""

    /** Processes one EMAIL and one SMS event through the real consumer and returns channel to (state, reason). */
    private fun process(context: ApplicationContext): Map<String, Pair<String, String?>> {
        val tenant = TenantId(UUID.randomUUID())
        val log = Log()
        log.add(envelope(tenant, emailEvent))
        log.add(envelope(tenant, smsEvent))
        val scope = context.getBean(TenantScope::class.java)
        val dsl = context.getBean(DSLContext::class.java)
        val runner = context.getBean(TransactionRunner::class.java)
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    MessageRequestedConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID(),
                    MessageRequestedConsumerConfiguration.TOPIC,
                    MessageRequestedConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("messageRequestedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
        consumer.pollOnce().applied shouldBe 2
        return scope.bind(tenant, actor) {
            runner.inTransaction {
                dsl.selectFrom(MESSAGE_DELIVERY).fetch().associate { it.channel!! to (it.state!! to it.failureReason) }
            }
        }
    }

    private fun deliveries(context: ApplicationContext) = context.getBeansOfType(MessageDelivery::class.java).values

    @Test
    @DisplayName(
        "INV-AC-26568 with no key set no MessageDelivery bean exists and both records are FAILED CHANNEL_NOT_BOUND",
    )
    fun noKeySet(
        @TempDir dir: Path,
    ) {
        FakeSmtpServer().use { server ->
            NotificationContext.online(dir).run { context ->
                (context.startupFailure == null) shouldBe true
                deliveries(context).size shouldBe 0
                process(context) shouldBe
                    mapOf("EMAIL" to ("FAILED" to "CHANNEL_NOT_BOUND"), "SMS" to ("FAILED" to "CHANNEL_NOT_BOUND"))
                server.mailCommandCount shouldBe 0
            }
        }
    }

    @Test
    @DisplayName(
        "INV-AC-26568 with all keys set one EMAIL bean exists, the EMAIL record is SENT and the SMS record FAILED",
    )
    fun allKeysSet(
        @TempDir dir: Path,
    ) {
        FakeSmtpServer().use { server ->
            runner(dir, server).run { context ->
                (context.startupFailure == null) shouldBe true
                deliveries(context).map { it.channel } shouldBe listOf(MessageChannel.EMAIL)
                process(context) shouldBe
                    mapOf("EMAIL" to ("SENT" to null), "SMS" to ("FAILED" to "CHANNEL_NOT_BOUND"))
                server.messages.size shouldBe 1
            }
        }
    }

    @ParameterizedTest(name = "INV-AC-26568 with the key {0} removed the start fails naming the key and no value")
    @ValueSource(
        strings = [
            SmtpDeliveryProperties.HOST,
            SmtpDeliveryProperties.PORT,
            SmtpDeliveryProperties.FROM_ADDRESS,
            SmtpDeliveryProperties.CREDENTIAL_SECRET_REF,
            SmtpDeliveryProperties.CONNECT_TIMEOUT_MS,
            SmtpDeliveryProperties.READ_TIMEOUT_MS,
            SmtpDeliveryProperties.WRITE_TIMEOUT_MS,
            SmtpDeliveryProperties.STATUS_TABLE_SIZE,
        ],
    )
    fun oneKeyMissing(
        key: String,
        @TempDir dir: Path,
    ) {
        FakeSmtpServer().use { server ->
            runner(dir, server, key).run { context ->
                val failure = context.startupFailure
                (failure != null) shouldBe true
                val text = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
                text shouldContain key
                for (value in listOf("sender-9x7", "relay-ref-9x7", "2001", "5002", "5003", "${server.port}")) {
                    text shouldNotContain value
                }
                server.mailCommandCount shouldBe 0
            }
        }
    }
}
