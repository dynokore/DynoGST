package com.dynokore.gst.notification.adapters.delivery

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

/** INV-REQ-817: the HTTPS mail API adapter of the EMAIL channel, against a JDK HttpsServer double. */
class HttpsMailApiMessageDeliveryTest {
    private val to = "recipient.secret@example.test"
    private val link = "link-ref-7f3a9c"
    private val credential = "api-credential-4b8d"
    private val from = "noreply@gst.example.test"
    private val server = FakeHttpsApiServer()
    private val logback = ListAppender<ILoggingEvent>()
    private val seen = mutableListOf<String>()
    private val ids = AtomicInteger()

    private class FakeSecrets(
        private val value: String,
    ) : SecretStore {
        override fun read(ref: SecretRef): Secret {
            check(ref.value == "mail/api") { "unexpected ref" }
            return Secret(value)
        }
    }

    @BeforeEach
    fun capture() {
        logback.start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logback)
    }

    @AfterEach
    fun release() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logback)
        server.close()
    }

    private fun properties(
        url: String = server.url(),
        tableSize: Int = 2,
    ) = HttpsMailApiDeliveryProperties(url, from, "mail/api", CONNECT_MS, REQUEST_MS, tableSize)

    private fun client(context: SSLContext = server.clientContext) =
        HttpClient
            .newBuilder()
            .sslContext(context)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(CONNECT_MS.toLong()))
            .build()

    private fun adapter(
        context: SSLContext = server.clientContext,
        url: String = server.url(),
    ) = HttpsMailApiMessageDelivery(properties(url), client(context), FakeSecrets(credential)) {
        "id-${ids.incrementAndGet()}"
    }

    private fun email() = OutboundMessage(MessageChannel.EMAIL, to, "access-link", "v1", LinkContent(link))

    /** Sends one message and returns the failed reason, recording every text the adapter produced. */
    private fun failedReason(
        a: HttpsMailApiMessageDelivery,
        message: OutboundMessage = email(),
    ): String? {
        val id = a.send(message).deliveryId
        val s = a.status(id)
        s.state shouldBe DeliveryState.FAILED
        seen += s.reason.orEmpty()
        return s.reason
    }

    @Test
    fun `INV-AC-27588 request shape and receipt - one POST with bearer, idempotency key and JSON body, SENT`() {
        val a = adapter()
        val receipt = a.send(email())
        server.requests.size shouldBe 1
        val r = server.requests.single()
        r.method shouldBe "POST"
        r.path shouldBe "/v1/send"
        r.headers["authorization"] shouldBe "Bearer $credential"
        r.headers["idempotency-key"] shouldBe receipt.deliveryId
        r.headers["content-type"] shouldBe "application/json"
        val json = JsonMapper.builder().build().readTree(r.body)
        json["to"].asString() shouldBe to
        json["from"].asString() shouldBe from
        json["messageId"].asString() shouldBe receipt.deliveryId
        json["subject"].asString() shouldBe "access-link"
        json["text"].asString() shouldContain link
        val status = a.status(receipt.deliveryId)
        status.state shouldBe DeliveryState.SENT
        status.reason shouldBe null
    }

    @Test
    fun `INV-AC-27588 400 and redirect - FAILED API_REJECTED and the redirect is not followed`() {
        val a = adapter()
        server.answer = 400
        failedReason(a) shouldBe "API_REJECTED"
        server.requests.clear()
        server.redirectTo = "/elsewhere"
        failedReason(a) shouldBe "API_REJECTED"
        server.requests.size shouldBe 1
    }

    @Test
    fun `INV-AC-27588 401 and 403 - FAILED API_AUTH_FAILED`() {
        val a = adapter()
        server.answer = 401
        failedReason(a) shouldBe "API_AUTH_FAILED"
        server.answer = 403
        failedReason(a) shouldBe "API_AUTH_FAILED"
    }

    @Test
    fun `INV-AC-27588 429, 503, delay and untrusted certificate - FAILED API_UNAVAILABLE`() {
        val a = adapter()
        server.answer = 429
        failedReason(a) shouldBe "API_UNAVAILABLE"
        server.answer = 503
        failedReason(a) shouldBe "API_UNAVAILABLE"
        server.answer = 202
        server.delayMs = (REQUEST_MS * 4).toLong()
        failedReason(a) shouldBe "API_UNAVAILABLE"
        server.delayMs = 0
        server.requests.clear()
        failedReason(adapter(server.untrustedClientContext)) shouldBe "API_UNAVAILABLE"
        server.requests.size shouldBe 0
    }

    @Test
    fun `INV-AC-27588 SMS message refused - IllegalArgumentException and nothing received`() {
        val a = adapter()
        val sms = OutboundMessage(MessageChannel.SMS, "+6570000000", "passcode", "v1", LinkContent(link))
        assertThrows<IllegalArgumentException> { a.send(sms) }
        server.requests.size shouldBe 0
    }

    @Test
    fun `INV-AC-27588 unknown and evicted id - FAILED DELIVERY_UNKNOWN`() {
        val a = adapter()
        val first = a.send(email()).deliveryId
        a.send(email())
        a.status(first).state shouldBe DeliveryState.SENT
        a.send(email())
        val evicted = a.status(first)
        evicted.state shouldBe DeliveryState.FAILED
        evicted.reason shouldBe "DELIVERY_UNKNOWN"
        val never = a.status("never-issued")
        never.state shouldBe DeliveryState.FAILED
        never.reason shouldBe "DELIVERY_UNKNOWN"
    }

    @Test
    fun `INV-AC-27588 http endpoint refused - IllegalArgumentException names the key and no value`() {
        val url = "http://secret-host.example.test/v1/send"
        val e = assertThrows<IllegalArgumentException> { adapter(url = url) }
        e.message!! shouldContain "gst.message-delivery.email.api.endpoint-url"
        e.message!! shouldNotContain "secret-host"
        assertThrows<IllegalArgumentException> { adapter(url = "not a url") }
    }

    @Test
    fun `INV-AC-27588 secrecy - no reason, log line or exception message holds the address, credential or content`() {
        val a = adapter()
        listOf(400, 401, 403, 429, 503).forEach {
            server.answer = it
            failedReason(a)
        }
        server.answer = 202
        server.delayMs = (REQUEST_MS * 4).toLong()
        failedReason(a)
        server.delayMs = 0
        failedReason(adapter(server.untrustedClientContext))
        seen += assertThrows<IllegalArgumentException> {
            a.send(OutboundMessage(MessageChannel.SMS, to, "passcode", "v1", LinkContent(link)))
        }.message.orEmpty()
        seen += assertThrows<IllegalArgumentException> { adapter(url = "http://$to/$credential/$link") }.message.orEmpty()
        seen += logback.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        (logback.list.isNotEmpty()) shouldBe true
        seen.forEach { text ->
            listOf(to, credential, link).forEach { secret -> text shouldNotContain secret }
        }
    }

    private companion object {
        const val CONNECT_MS = 2000
        const val REQUEST_MS = 400
    }
}
