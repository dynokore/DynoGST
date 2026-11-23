package com.dynokore.gst.notification.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.delivery.RsaOaepPasscodeOpener
import com.dynokore.gst.notification.application.delivery.PasscodeOpener
import com.dynokore.gst.notification.application.delivery.PasscodeUnopenable
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
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * INV-AC-26537 through the real inbox consumer, the real handler and use case built by the TPL-18 context, the jOOQ
 * adapters and the Flyway-built schema (P109). Faked: the event-log read, the two channels (recording) and, where the
 * secret is bound, the `SecretStore` (a primary `withBean` override). The envelopes are sealed here as access-svc
 * seals them: `v1.<keyId>.<ciphertext>`, RSAES-OAEP SHA-256, MGF1-SHA-256, label = the recipient address.
 */
class PasscodeOpeningBoundaryTest {
    private val json = EnvelopeJson()
    private var sequence = 0L
    private val recipient = "+6591234567"
    private val clear = "CLEAR-PASSCODE-7f3k9x"

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Fake(
        override val channel: MessageChannel,
    ) : MessageDelivery {
        val sent = mutableListOf<OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            sent += message
            return DeliveryReceipt("d-${channel.name}-${sent.size}")
        }

        override fun status(deliveryId: String) = DeliveryStatus(deliveryId, DeliveryState.SENT)
    }

    /** Holds the private key under the delivery reference; any other reference is not bound. */
    private class BoundKey(
        private val pem: String,
        private val mountRoot: Path,
    ) : SecretStore {
        // every other reference (the database login, the binding key) is the mounted file the context needs to start
        override fun read(ref: SecretRef): Secret =
            when {
                ref == SecretRef(RsaOaepPasscodeOpener.SECRET_REF) -> Secret(pem)
                Files.exists(mountRoot.resolve(ref.value)) -> Secret(Files.readString(mountRoot.resolve(ref.value)))
                else -> throw SecretNotFound(ref)
            }
    }

    private class Boundary(
        val context: ApplicationContext,
        val log: Log,
        val email: Fake,
        val sms: Fake,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val raised = mutableListOf<Throwable>()

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
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

        fun poll() {
            try {
                consumer.pollOnce()
            } catch (e: Exception) {
                raised += e
            }
        }
    }

    private fun run(
        dir: Path,
        secretBound: Boolean = true,
        block: Boundary.(TenantId, ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val log = Log()
        val email = Fake(MessageChannel.EMAIL)
        val sms = Fake(MessageChannel.SMS)
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val before = root.level
        root.level = Level.TRACE
        root.addAppender(appender)
        try {
            var runner =
                NotificationContext
                    .online(dir)
                    .withBean(EventLogReader::class.java, { log }, BeanDefinitionCustomizer { it.isPrimary = true })
                    .withBean("emailChannel", MessageDelivery::class.java, { email })
                    .withBean("smsChannel", MessageDelivery::class.java, { sms })
            if (secretBound) {
                runner =
                    runner.withBean(
                        SecretStore::class.java,
                        { BoundKey(PEM, dir) },
                        BeanDefinitionCustomizer {
                            it.isPrimary =
                                true
                        },
                    )
            }
            runner.run { context ->
                check(context.startupFailure == null) {
                    "the context did not start: " +
                        generateSequence(context.startupFailure) { it.cause }.joinToString(" <- ") {
                            it.javaClass.simpleName +
                                ":" +
                                it.message?.take(300)
                        }
                }
                Boundary(context, log, email, sms).block(TenantId(UUID.randomUUID()), appender)
            }
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
    }

    private fun envelope(
        tenant: TenantId,
        data: String,
    ): String =
        json.write(
            Envelope(
                id = EventId("ev-" + UUID.randomUUID()),
                source = "/gst/access-svc",
                type = "com.dynokore.gst.access.MessageRequested.v1",
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

    private fun passcodeMessage(sealed: String) =
        """{"template":"signed-link-passcode","recipients":["$recipient"],"channel":"SMS","sealedPasscode":"$sealed"}"""

    private fun seal(
        label: String,
        value: String = clear,
    ): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            KEYS.public,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(label.toByteArray())),
        )
        val ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(value.toByteArray()))
        val keyId = MessageDigest.getInstance("SHA-256").digest(KEYS.public.encoded).take(8)
        return "v1." + keyId.joinToString("") { "%02x".format(it) } + "." + ciphertext
    }

    /** Every row of every named table, each as one text (`t::text`), read as the database owner so no tenant filter hides a row. */
    private fun allRows(): List<String> =
        NotificationDb.superuser().use { c ->
            TABLES.flatMap { table ->
                c.createStatement().use { st ->
                    st.executeQuery("select t::text from $table t").use { rs ->
                        generateSequence { if (rs.next()) "$table: ${rs.getString(1)}" else null }.toList()
                    }
                }
            }
        }

    private fun deliveryRows(tenant: TenantId): List<List<String?>> =
        NotificationDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select state, failure_reason from notification.message_delivery where tenant_id = ?::uuid order by position",
                ).use { st ->
                    st.setString(1, tenant.value.toString())
                    st.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) listOf(rs.getString(1), rs.getString(2)) else null }.toList()
                    }
                }
        }

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun logText(appender: ListAppender<ILoggingEvent>): String =
        appender.list.joinToString("\n") { event ->
            event.formattedMessage + " " + (event.throwableProxy?.let { stack((it as ThrowableProxy).throwable) } ?: "")
        }

    @Test
    @DisplayName(
        "INV-AC-26537 the clear passcode reaches the channel once and appears in no row of message_delivery, inbox, dead letter or outbox",
    )
    fun clearReachesChannelOnceAndIsStoredNowhere(
        @TempDir dir: Path,
    ) = run(dir) { tenant, _ ->
        val sealed = seal(recipient)
        log.add(envelope(tenant, passcodeMessage(sealed)))
        poll()
        raised shouldBe emptyList()
        sms.sent.map { it.content } shouldBe listOf(PasscodeContent(clear))
        email.sent.size shouldBe 0
        deliveryRows(tenant) shouldBe listOf(listOf("SENT", null))
        val rows = allRows()
        rows.any { it.startsWith("notification.message_delivery: ") } shouldBe true
        rows.any { it.startsWith("notification.inbox: ") } shouldBe true
        rows.forEach {
            it shouldNotContain clear
            it shouldNotContain sealed
        }
    }

    @Test
    @DisplayName(
        "INV-AC-26537 no log line and no exception text of the run contains the clear passcode or the sealed string",
    )
    fun noLogNoExceptionHoldsThePasscode(
        @TempDir dir: Path,
    ) = run(dir) { tenant, appender ->
        val sealed = seal(recipient)
        val other = seal("someone-else@mail.test")
        log.add(envelope(tenant, passcodeMessage(sealed)))
        log.add(envelope(tenant, passcodeMessage(other)))
        poll()
        val opener = context.getBean(PasscodeOpener::class.java)
        val refused = runCatching { opener.open(other, recipient) }.exceptionOrNull()
        (refused is PasscodeUnopenable) shouldBe true
        val texts = (raised + refused!!).map { stack(it) }
        (appender.list.size > 0) shouldBe true
        (texts + logText(appender)).forEach {
            it shouldNotContain clear
            it shouldNotContain sealed
            it shouldNotContain other
        }
        sms.sent.size shouldBe 1
    }

    @Test
    @DisplayName(
        "INV-AC-26537 an envelope sealed for another label gives FAILED PASSCODE_UNOPENABLE and no channel call",
    )
    fun otherLabelFailsClosed(
        @TempDir dir: Path,
    ) = run(dir) { tenant, _ ->
        log.add(envelope(tenant, passcodeMessage(seal("someone-else@mail.test"))))
        poll()
        raised shouldBe emptyList()
        deliveryRows(tenant) shouldBe listOf(listOf("FAILED", "PASSCODE_UNOPENABLE"))
        sms.sent.size + email.sent.size shouldBe 0
    }

    @Test
    @DisplayName(
        "INV-AC-26537 with no secret bound the context starts, a link message is delivered and a passcode message is FAILED PASSCODE_UNOPENABLE",
    )
    fun noSecretContextStarts(
        @TempDir dir: Path,
    ) = run(dir, secretBound = false) { tenant, _ ->
        context.getBeanNamesForType(PasscodeOpener::class.java).size shouldBe 1
        context.getBeansOfType(RsaOaepPasscodeOpener::class.java).size shouldBe 1
        log.add(
            envelope(
                tenant,
                """{"template":"signed-link","recipients":["a@mail.test"],"channel":"EMAIL","linkReference":"link-ref-1"}""",
            ),
        )
        log.add(envelope(tenant, passcodeMessage(seal(recipient))))
        poll()
        raised shouldBe emptyList()
        deliveryRows(tenant).sortedBy { it[0] } shouldBe
            listOf(listOf("FAILED", "PASSCODE_UNOPENABLE"), listOf("SENT", null))
        email.sent.size shouldBe 1
        sms.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26537 the whole row of every table is scanned as text")
    fun everyTableIsScannedWhole(
        @TempDir dir: Path,
    ) = run(dir) { tenant, _ ->
        TABLES shouldBe
            listOf(
                "notification.message_delivery",
                "notification.inbox",
                "notification.event_dead_letter",
                "notification.outbox",
            )
        log.add(envelope(tenant, passcodeMessage(seal(recipient))))
        poll()
        // the scan reads the row as one text (t::text), so a column added later is covered without a change here
        val whole =
            allRows().filter {
                it.startsWith(
                    "notification.message_delivery: ",
                ) && tenant.value.toString() in it
            }
        whole.single() shouldNotContain clear
        whole.single().contains("SENT") shouldBe true
    }

    private companion object {
        val TABLES =
            listOf(
                "notification.message_delivery",
                "notification.inbox",
                "notification.event_dead_letter",
                "notification.outbox",
            )
        val KEYS by lazy {
            KeyPairGenerator.getInstance("RSA").also { it.initialize(RsaOaepPasscodeOpener.MIN_BITS) }.generateKeyPair()
        }
        val PEM by lazy {
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(KEYS.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        }
    }
}
