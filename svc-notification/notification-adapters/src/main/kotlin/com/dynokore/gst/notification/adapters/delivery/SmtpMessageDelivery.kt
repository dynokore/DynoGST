package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/**
 * The SMTP relay [MessageDelivery] of the EMAIL channel (INV-REQ-817). One SMTP session per send with STARTTLS
 * required and the server identity checked (RFC 3207, RFC 8314; OWASP ASVS V9). The credential is read through the
 * SecretStore at send time (first line the user, the rest the password) and never logged.
 *
 * A failure is not thrown: it is kept as the status of the returned id, with a closed reason code (never an exception
 * message, address or content). Statuses live in a bounded in-memory table, oldest first out; an id not in it is
 * FAILED [DELIVERY_UNKNOWN], never SENT. SMTP acceptance is SENT; DELIVERED and BOUNCED need bounce processing,
 * which this adapter does not do.
 *
 * [trustedSocketFactory] is the trust anchor hook for a relay behind a private CA; null means the JVM default trust.
 * Server identity checking stays on either way.
 */
class SmtpMessageDelivery(
    private val properties: SmtpDeliveryProperties,
    private val secrets: SecretStore,
    private val idSource: () -> String = { "<${UUID.randomUUID()}@gst-notification>" },
    private val trustedSocketFactory: SSLSocketFactory? = null,
) : MessageDelivery {
    override val channel: MessageChannel = MessageChannel.EMAIL

    private val table: MutableMap<String, DeliveryStatus> =
        object : LinkedHashMap<String, DeliveryStatus>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeliveryStatus>): Boolean =
                size > properties.statusTableSize
        }

    override fun send(message: OutboundMessage): DeliveryReceipt {
        require(message.channel == MessageChannel.EMAIL) { "the SMTP adapter serves the EMAIL channel only" }
        val id = idSource()
        val status =
            try {
                transmit(message, id)
                DeliveryStatus(id, DeliveryState.SENT)
            } catch (e: Exception) {
                val reason = classify(e)
                LOG.warn("smtp send failed, reason={}", reason)
                DeliveryStatus(id, DeliveryState.FAILED, reason)
            }
        synchronized(table) { table[id] = status }
        return DeliveryReceipt(id)
    }

    override fun status(deliveryId: String): DeliveryStatus =
        synchronized(table) { table[deliveryId] }
            ?: DeliveryStatus(deliveryId, DeliveryState.FAILED, DELIVERY_UNKNOWN)

    private fun transmit(
        message: OutboundMessage,
        id: String,
    ) {
        val credential = secrets.read(SecretRef(properties.credentialSecretRef)).reveal()
        val user = credential.substringBefore('\n').trim()
        val password = credential.substringAfter('\n', "").trim()
        val session = Session.getInstance(sessionProperties())
        val mime =
            object : MimeMessage(session) {
                override fun updateMessageID() {
                    setHeader("Message-ID", id)
                }
            }
        mime.setFrom(InternetAddress(properties.fromAddress))
        mime.setRecipient(Message.RecipientType.TO, InternetAddress(message.to))
        mime.setSubject(message.templateId, "UTF-8")
        val value =
            when (val content = message.content) {
                is LinkContent -> content.linkReference
                is PasscodeContent -> content.sealedPasscode
            }
        mime.setText("${message.templateId}\n${message.templateVersion}\n$value\n", "UTF-8")
        mime.saveChanges()
        session.getTransport("smtp").use { transport ->
            transport.connect(properties.host, properties.port, user, password)
            transport.sendMessage(mime, mime.allRecipients)
        }
    }

    private fun sessionProperties(): Properties =
        Properties().apply {
            put("mail.smtp.host", properties.host)
            put("mail.smtp.port", properties.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.connectiontimeout", properties.connectTimeoutMs.toString())
            put("mail.smtp.timeout", properties.readTimeoutMs.toString())
            put("mail.smtp.writetimeout", properties.writeTimeoutMs.toString())
            trustedSocketFactory?.let { put("mail.smtp.ssl.socketFactory", it) }
        }

    private fun classify(failure: Throwable): String {
        val chain =
            generateSequence(
                failure,
            ) { it.cause ?: (it as? MessagingException)?.nextException }.take(DEPTH).toList()
        val codes = chain.mapNotNull { returnCode(it) }
        return when {
            chain.any { it is AuthenticationFailedException } -> SMTP_AUTH_FAILED
            chain.any { it is SSLException } || chain.any { requiresTls(it) } -> SMTP_TLS_REQUIRED
            codes.any { it in REJECT_CODES } -> SMTP_REJECTED
            else -> SMTP_UNAVAILABLE
        }
    }

    private fun requiresTls(e: Throwable): Boolean =
        e is MessagingException && e.message?.startsWith(TLS_REQUIRED_PREFIX) == true

    private fun returnCode(e: Throwable): Int? =
        when (e) {
            is SMTPSendFailedException -> e.returnCode
            is SMTPAddressFailedException -> e.returnCode
            else -> null
        }

    companion object {
        const val SMTP_TLS_REQUIRED = "SMTP_TLS_REQUIRED"
        const val SMTP_REJECTED = "SMTP_REJECTED"
        const val SMTP_UNAVAILABLE = "SMTP_UNAVAILABLE"
        const val SMTP_AUTH_FAILED = "SMTP_AUTH_FAILED"
        const val DELIVERY_UNKNOWN = "DELIVERY_UNKNOWN"

        private const val TLS_REQUIRED_PREFIX = "STARTTLS is required"
        private const val DEPTH = 8
        private val REJECT_CODES = 500..599
        private val LOG = LoggerFactory.getLogger(SmtpMessageDelivery::class.java)
    }
}
