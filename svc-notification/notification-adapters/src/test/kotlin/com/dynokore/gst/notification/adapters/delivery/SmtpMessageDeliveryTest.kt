package com.dynokore.gst.notification.adapters.delivery

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/** INV-REQ-817: the SMTP relay adapter of the EMAIL channel, against a socket double that speaks STARTTLS. */
class SmtpMessageDeliveryTest {
    private val to = "recipient.secret@example.test"
    private val link = "link-ref-7f3a9c"
    private val user = "relay-user"
    private val password = "relay-password"
    private val sealedPasscode = "sealed-passcode-5d1e"
    private val servers = mutableListOf<FakeSmtpServer>()
    private val logged = mutableListOf<String>()
    private val logback = ListAppender<ILoggingEvent>()
    private val jul =
        object : Handler() {
            override fun publish(record: LogRecord) {
                logged += "${record.message} ${record.parameters?.joinToString().orEmpty()} ${record.thrown ?: ""}"
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

    private class FakeSecrets(
        private val value: String,
    ) : SecretStore {
        override fun read(ref: SecretRef): Secret {
            check(ref.value == "smtp/relay") { "unexpected ref" }
            return Secret(value)
        }
    }

    @BeforeEach
    fun capture() {
        logback.start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logback)
        JulLogger.getLogger("").addHandler(jul)
    }

    @AfterEach
    fun release() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logback)
        JulLogger.getLogger("").removeHandler(jul)
        servers.forEach { it.close() }
    }

    private fun server(vararg args: Pair<String, String>): FakeSmtpServer {
        val m = args.toMap()
        return FakeSmtpServer(
            offerStartTls = m["starttls"] != "no",
            greeting = m["greeting"] ?: "220 localhost ESMTP",
            authReply = m["auth"] ?: "235 2.7.0 accepted",
            rcptReply = m["rcpt"] ?: "250 2.1.5 ok",
            user = user,
            password = password,
        ).also { servers += it }
    }

    private val ids = AtomicInteger()

    private fun adapter(
        port: Int,
        factory: javax.net.ssl.SSLSocketFactory?,
        tableSize: Int = 2,
    ) = SmtpMessageDelivery(
        SmtpDeliveryProperties(
            host = "localhost",
            port = port,
            fromAddress = "noreply@gst.example.test",
            credentialSecretRef = "smtp/relay",
            connectTimeoutMs = 2000,
            readTimeoutMs = 5000,
            writeTimeoutMs = 5000,
            statusTableSize = tableSize,
        ),
        FakeSecrets("$user\n$password"),
        { "<id-${ids.incrementAndGet()}@test>" },
        factory,
    )

    private fun email(content: com.dynokore.platform.serviceruntime.binding.MessageContent = LinkContent(link)) =
        OutboundMessage(MessageChannel.EMAIL, to, "access-link", "v1", content)

    private fun reasonOf(
        a: SmtpMessageDelivery,
        id: String,
    ): String? {
        val s = a.status(id)
        s.state shouldBe DeliveryState.FAILED
        return s.reason
    }

    @Test
    fun `INV-AC-26567 delivery and receipt - one message with RCPT, Message-ID and link, status SENT`() {
        val srv = server()
        val a = adapter(srv.port, srv.clientSocketFactory)
        val receipt = a.send(email())
        srv.messages.size shouldBe 1
        val got = srv.messages.single()
        got.rcpt shouldBe listOf(to)
        got.header("Message-ID") shouldBe receipt.deliveryId
        (link in got.data) shouldBe true
        val status = a.status(receipt.deliveryId)
        status.state shouldBe DeliveryState.SENT
        status.reason shouldBe null
    }

    @Test
    fun `INV-AC-26567 no STARTTLS offered - no MAIL command and FAILED SMTP_TLS_REQUIRED`() {
        val srv = server("starttls" to "no")
        val a = adapter(srv.port, srv.clientSocketFactory)
        val receipt = a.send(email())
        srv.mailCommandCount shouldBe 0
        reasonOf(a, receipt.deliveryId) shouldBe "SMTP_TLS_REQUIRED"
    }

    @Test
    fun `INV-AC-26567 certificate not trusted - no MAIL command and FAILED SMTP_TLS_REQUIRED`() {
        val srv = server()
        val a = adapter(srv.port, null)
        val receipt = a.send(email())
        srv.mailCommandCount shouldBe 0
        reasonOf(a, receipt.deliveryId) shouldBe "SMTP_TLS_REQUIRED"
    }

    @Test
    fun `INV-AC-26567 server identity mismatch - FAILED SMTP_TLS_REQUIRED`() {
        val srv = server()
        val a =
            SmtpMessageDelivery(
                SmtpDeliveryProperties("127.0.0.1", srv.port, "n@x.test", "smtp/relay", 2000, 5000, 5000, 2),
                FakeSecrets("$user\n$password"),
                { "<mismatch@test>" },
                srv.clientSocketFactory,
            )
        val receipt = a.send(email())
        srv.mailCommandCount shouldBe 0
        reasonOf(a, receipt.deliveryId) shouldBe "SMTP_TLS_REQUIRED"
    }

    @Test
    fun `INV-AC-26567 RCPT answered 550 - FAILED SMTP_REJECTED`() {
        val srv = server("rcpt" to "550 5.1.1 no such user")
        val a = adapter(srv.port, srv.clientSocketFactory)
        reasonOf(a, a.send(email()).deliveryId) shouldBe "SMTP_REJECTED"
    }

    @Test
    fun `INV-AC-26567 connection refused - FAILED SMTP_UNAVAILABLE`() {
        val a = adapter(FakeSmtpServer.closedPort(), null)
        reasonOf(a, a.send(email()).deliveryId) shouldBe "SMTP_UNAVAILABLE"
    }

    @Test
    fun `INV-AC-26567 answer 421 - FAILED SMTP_UNAVAILABLE`() {
        val greeting = server("greeting" to "421 4.3.2 shutting down")
        val g = adapter(greeting.port, greeting.clientSocketFactory)
        reasonOf(g, g.send(email()).deliveryId) shouldBe "SMTP_UNAVAILABLE"
        val rcpt = server("rcpt" to "421 4.3.2 shutting down")
        val r = adapter(rcpt.port, rcpt.clientSocketFactory)
        reasonOf(r, r.send(email()).deliveryId) shouldBe "SMTP_UNAVAILABLE"
    }

    @Test
    fun `INV-AC-26567 AUTH answered 535 - no MAIL command and FAILED SMTP_AUTH_FAILED not SMTP_REJECTED`() {
        val srv = server("auth" to "535 5.7.8 authentication failed")
        val a = adapter(srv.port, srv.clientSocketFactory)
        val receipt = a.send(email())
        srv.mailCommandCount shouldBe 0
        reasonOf(a, receipt.deliveryId) shouldBe "SMTP_AUTH_FAILED"
    }

    @Test
    fun `INV-AC-26567 SMS message - send throws IllegalArgumentException and the server receives nothing`() {
        val srv = server()
        val a = adapter(srv.port, srv.clientSocketFactory)
        val sms =
            OutboundMessage(MessageChannel.SMS, "+6580000000", "access-passcode", "v1", PasscodeContent(sealedPasscode))
        runCatching { a.send(sms) }.exceptionOrNull()!!.javaClass shouldBe IllegalArgumentException::class.java
        srv.mailCommandCount shouldBe 0
        srv.messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-26567 unknown id and evicted id - FAILED DELIVERY_UNKNOWN`() {
        val srv = server()
        val a = adapter(srv.port, srv.clientSocketFactory, tableSize = 2)
        reasonOf(a, "<never-issued@test>") shouldBe "DELIVERY_UNKNOWN"
        val first = a.send(email()).deliveryId
        val second = a.send(email()).deliveryId
        a.status(first).state shouldBe DeliveryState.SENT
        val third = a.send(email()).deliveryId
        reasonOf(a, first) shouldBe "DELIVERY_UNKNOWN"
        a.status(second).state shouldBe DeliveryState.SENT
        a.status(third).state shouldBe DeliveryState.SENT
    }

    @Test
    fun `INV-AC-26567 secrecy - no reason, log line or exception text holds the address, credentials or content`() {
        val secrets = listOf(to, user, password, link, sealedPasscode)
        val texts = mutableListOf<String>()
        val cases =
            listOf(
                server() to true,
                server("starttls" to "no") to true,
                server("rcpt" to "550 5.1.1 no such user") to true,
                server("auth" to "535 5.7.8 authentication failed") to true,
                server("rcpt" to "421 4.3.2 shutting down") to true,
            )
        for ((srv, _) in cases) {
            val a = adapter(srv.port, srv.clientSocketFactory)
            val id = a.send(email()).deliveryId
            texts += a.status(id).toString()
            a.status(id).reason?.let { texts += it }
        }
        val refused = adapter(FakeSmtpServer.closedPort(), null)
        texts += refused.status(refused.send(email()).deliveryId).toString()
        val any = adapter(servers.first().port, servers.first().clientSocketFactory)
        val sms = OutboundMessage(MessageChannel.SMS, to, "access-passcode", "v1", PasscodeContent(sealedPasscode))
        texts += runCatching { any.send(sms) }.exceptionOrNull()!!.message.orEmpty()
        texts += logback.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        texts += logged
        (texts.size > 6) shouldBe true
        for (text in texts) {
            for (secret in secrets) {
                (secret in text) shouldBe false
            }
        }
        (logback.list.isNotEmpty()) shouldBe true
    }
}
