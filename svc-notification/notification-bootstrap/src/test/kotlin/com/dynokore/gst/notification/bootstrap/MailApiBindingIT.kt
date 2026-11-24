package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.FakeHttpsApiServer
import com.dynokore.gst.notification.adapters.delivery.HttpsMailApiDeliveryProperties
import com.dynokore.gst.notification.adapters.delivery.HttpsMailApiMessageDelivery
import com.dynokore.gst.notification.adapters.delivery.SmtpDeliveryProperties
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
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
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
 * INV-AC-27590: the EMAIL channel is bound to the mail API adapter only when every mail API key is configured and no
 * SMTP host is set, over the Flyway-built schema with the delivery chain wired as in production (the real bean of
 * [MessageDeliveryConfiguration], the real `MessageChannels`, the real handler). The https URL is the double's; a bean
 * is built and nothing is expected to be sent (the production client trusts the JVM store, not the double).
 */
class MailApiBindingIT {
    private val actor = "svc:notification-svc"
    private val json = EnvelopeJson()
    private var sequence = 0L

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
            HttpsMailApiDeliveryProperties.FROM_ADDRESS to "sender-9x7@api-fail.test",
            HttpsMailApiDeliveryProperties.CREDENTIAL_SECRET_REF to "mail/api-ref-9x7",
            HttpsMailApiDeliveryProperties.CONNECT_TIMEOUT_MS to "2001",
            HttpsMailApiDeliveryProperties.REQUEST_TIMEOUT_MS to "5002",
            HttpsMailApiDeliveryProperties.STATUS_TABLE_SIZE to "17",
        )

    private fun runner(
        dir: Path,
        endpoint: String,
        vararg without: String,
    ): ApplicationContextRunner {
        val ref = allKeys.getValue(HttpsMailApiDeliveryProperties.CREDENTIAL_SECRET_REF)
        Files.createDirectories(dir.resolve(ref).parent)
        Files.writeString(dir.resolve(ref), "api-user\napi-token")
        val values =
            (allKeys + (HttpsMailApiDeliveryProperties.ENDPOINT_URL to endpoint))
                .filterKeys { it !in without }
                .map { "${it.key}=${it.value}" }
        return NotificationContext.online(dir).withPropertyValues(*values.toTypedArray())
    }

    private fun failureText(context: AssertableApplicationContext): String {
        val failure = context.startupFailure
        (failure != null) shouldBe true
        return generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
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

    private val values = listOf("sender-9x7", "api-ref-9x7", "2001", "5002", "api-user", "api-token")

    @Test
    @DisplayName(
        "INV-AC-27590 with no key set no MessageDelivery bean exists and both records are FAILED CHANNEL_NOT_BOUND",
    )
    fun noKeySet(
        @TempDir dir: Path,
    ) {
        NotificationContext.online(dir).run { context ->
            (context.startupFailure == null) shouldBe true
            deliveries(context).size shouldBe 0
            process(context) shouldBe
                mapOf("EMAIL" to ("FAILED" to "CHANNEL_NOT_BOUND"), "SMS" to ("FAILED" to "CHANNEL_NOT_BOUND"))
        }
    }

    @Test
    @DisplayName(
        "INV-AC-27590 with all keys set exactly one EMAIL bean exists, the mail API adapter, and SMS stays unbound",
    )
    fun allKeysSet(
        @TempDir dir: Path,
    ) {
        FakeHttpsApiServer().use { server ->
            runner(dir, server.url()).run { context ->
                (context.startupFailure == null) shouldBe true
                val beans = deliveries(context)
                beans.map { it.channel } shouldBe listOf(MessageChannel.EMAIL)
                (beans.single() is HttpsMailApiMessageDelivery) shouldBe true
                val records = process(context)
                records.getValue("SMS") shouldBe ("FAILED" to "CHANNEL_NOT_BOUND")
                (records.getValue("EMAIL").second != "CHANNEL_NOT_BOUND") shouldBe true
                server.requests.size shouldBe 0
            }
        }
    }

    @ParameterizedTest(name = "INV-AC-27590 with the key {0} removed the start fails naming the key and no value")
    @ValueSource(
        strings = [
            HttpsMailApiDeliveryProperties.ENDPOINT_URL,
            HttpsMailApiDeliveryProperties.FROM_ADDRESS,
            HttpsMailApiDeliveryProperties.CREDENTIAL_SECRET_REF,
            HttpsMailApiDeliveryProperties.CONNECT_TIMEOUT_MS,
            HttpsMailApiDeliveryProperties.REQUEST_TIMEOUT_MS,
            HttpsMailApiDeliveryProperties.STATUS_TABLE_SIZE,
        ],
    )
    fun oneKeyMissing(
        key: String,
        @TempDir dir: Path,
    ) {
        FakeHttpsApiServer().use { server ->
            runner(dir, server.url(), key).run { context ->
                val text = failureText(context)
                text shouldContain key
                for (value in values + server.url()) text shouldNotContain value
                server.requests.size shouldBe 0
            }
        }
    }

    @Test
    @DisplayName("INV-AC-27590 with the mail API keys and the SMTP host both set the start fails naming the two keys")
    fun bothSet(
        @TempDir dir: Path,
    ) {
        FakeHttpsApiServer().use { server ->
            runner(dir, server.url())
                .withPropertyValues("${SmtpDeliveryProperties.HOST}=smtp-host-9x7.test")
                .run { context ->
                    val text = failureText(context)
                    text shouldContain HttpsMailApiDeliveryProperties.ENDPOINT_URL
                    text shouldContain SmtpDeliveryProperties.HOST
                    for (value in values + listOf(server.url(), "smtp-host-9x7")) text shouldNotContain value
                    server.requests.size shouldBe 0
                }
        }
    }

    @Test
    @DisplayName("INV-AC-27590 with an http endpoint the start fails naming the endpoint key and no value")
    fun httpEndpoint(
        @TempDir dir: Path,
    ) {
        runner(dir, "http://mail-9x7.test:8080/v1/send").run { context ->
            val text = failureText(context)
            text shouldContain HttpsMailApiDeliveryProperties.ENDPOINT_URL
            for (value in values + "mail-9x7") text shouldNotContain value
        }
    }
}
