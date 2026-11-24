package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageContent
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.binding.TemplateValuesContent
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLParameters

/**
 * The HTTPS mail API [MessageDelivery] of the EMAIL channel (INV-REQ-817). One POST per send over https only, with a
 * bearer credential read through the SecretStore at send time and an Idempotency-Key equal to the delivery id.
 * Redirects are never followed; the TLS certificate and host name are verified by the injected client (OWASP ASVS V9).
 *
 * A failure is not thrown: it is kept as the status of the returned id with a closed reason code (never an exception
 * message, address, credential or content). Statuses live in a bounded in-memory table, oldest first out; an id not
 * in it is FAILED [DELIVERY_UNKNOWN], never SENT. A 2xx answer is SENT; DELIVERED and BOUNCED need a provider
 * callback, which this adapter does not have.
 */
class HttpsMailApiMessageDelivery(
    private val properties: HttpsMailApiDeliveryProperties,
    private val client: HttpClient,
    private val secrets: SecretStore,
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) : MessageDelivery {
    override val channel: MessageChannel = MessageChannel.EMAIL

    private val mapper: ObjectMapper = JsonMapper.builder().build()

    private val table: MutableMap<String, DeliveryStatus> =
        object : LinkedHashMap<String, DeliveryStatus>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeliveryStatus>): Boolean =
                size > properties.statusTableSize
        }

    override fun send(message: OutboundMessage): DeliveryReceipt {
        require(message.channel == MessageChannel.EMAIL) { "the mail API adapter serves the EMAIL channel only" }
        val id = idSource()
        val status = outcome(id, message)
        if (status.state == DeliveryState.FAILED) LOG.warn("mail api send failed, reason={}", status.reason)
        synchronized(table) { table[id] = status }
        return DeliveryReceipt(id)
    }

    override fun status(deliveryId: String): DeliveryStatus =
        synchronized(table) { table[deliveryId] }
            ?: DeliveryStatus(deliveryId, DeliveryState.FAILED, DELIVERY_UNKNOWN)

    private fun outcome(
        id: String,
        message: OutboundMessage,
    ): DeliveryStatus {
        val code =
            try {
                post(id, message)
            } catch (e: IOException) {
                return failed(id, API_UNAVAILABLE)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return failed(id, API_UNAVAILABLE)
            } catch (e: NoSuchElementException) {
                return failed(id, API_UNAVAILABLE)
            } catch (e: IllegalStateException) {
                return failed(id, API_UNAVAILABLE)
            }
        return when (code) {
            in OK -> DeliveryStatus(id, DeliveryState.SENT)
            in AUTH_CODES -> failed(id, API_AUTH_FAILED)
            TOO_MANY_REQUESTS, in SERVER_ERRORS -> failed(id, API_UNAVAILABLE)
            else -> failed(id, API_REJECTED)
        }
    }

    private fun failed(
        id: String,
        reason: String,
    ): DeliveryStatus = DeliveryStatus(id, DeliveryState.FAILED, reason)

    private fun post(
        id: String,
        message: OutboundMessage,
    ): Int {
        val credential = secrets.read(SecretRef(properties.credentialSecretRef)).reveal()
        val body =
            mapper.writeValueAsString(
                linkedMapOf(
                    "messageId" to id,
                    "from" to properties.fromAddress,
                    "to" to message.to,
                    "subject" to message.templateId,
                    "text" to "${message.templateId}\n${message.templateVersion}\n${contentText(message.content)}\n",
                ),
            )
        val request =
            HttpRequest
                .newBuilder(properties.endpoint)
                .timeout(Duration.ofMillis(properties.requestTimeoutMs.toLong()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $credential")
                .header("Idempotency-Key", id)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun contentText(content: MessageContent): String =
        when (content) {
            is LinkContent -> content.linkReference
            is PasscodeContent -> content.sealedPasscode
            is TemplateValuesContent -> content.values.toSortedMap().map { (k, v) -> "$k=$v" }.joinToString("\n")
        }

    companion object {
        const val API_AUTH_FAILED = "API_AUTH_FAILED"
        const val API_UNAVAILABLE = "API_UNAVAILABLE"
        const val API_REJECTED = "API_REJECTED"
        const val DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"

        private val OK = 200..299
        private val AUTH_CODES = setOf(401, 403)
        private const val TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = 500..599
        private val LOG = LoggerFactory.getLogger(HttpsMailApiMessageDelivery::class.java)

        /**
         * The production client: JVM default trust store (certificate and host name checked), TLS 1.2 or higher,
         * redirects never followed. A test passes its own client to trust its own certificate.
         */
        fun productionClient(properties: HttpsMailApiDeliveryProperties): HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs.toLong()))
                .sslParameters(SSLParameters().apply { protocols = arrayOf("TLSv1.3", "TLSv1.2") })
                .build()
    }
}
