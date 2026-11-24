package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.SmtpDeliveryProperties
import com.dynokore.gst.notification.adapters.delivery.SmtpMessageDelivery
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Binds the SMTP relay adapter to the EMAIL channel only when `gst.message-delivery.email.smtp.host` is set
 * (INV-REQ-817). Once the host is set every other key is required; a missing one stops the start with a message naming
 * the key only, never a value. With no key of the set at all, no `MessageDelivery` bean exists and the delivery chain
 * records FAILED CHANNEL_NOT_BOUND (fail closed). Other keys set without the host also stop the start, naming the
 * host key: a half-configured relay is never silently unbound. No default exists in code.
 */
@Configuration(proxyBeanMethods = false)
class MessageDeliveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = [SmtpDeliveryProperties.HOST])
    fun emailMessageDelivery(
        environment: Environment,
        secrets: SecretStore,
    ): MessageDelivery = SmtpMessageDelivery(SmtpDeliveryProperties.from(environment::getProperty), secrets)

    @Bean
    fun smtpKeySetCheck(environment: Environment): SmtpKeySetCheck {
        val others = KEYS.filter { it != SmtpDeliveryProperties.HOST && environment.containsProperty(it) }
        check(environment.containsProperty(SmtpDeliveryProperties.HOST) || others.isEmpty()) {
            "missing configuration key ${SmtpDeliveryProperties.HOST}"
        }
        return SmtpKeySetCheck
    }

    /** Marker of a passed check; the bean exists only so that the check runs at start. */
    object SmtpKeySetCheck

    companion object {
        private val KEYS =
            listOf(
                SmtpDeliveryProperties.HOST,
                SmtpDeliveryProperties.PORT,
                SmtpDeliveryProperties.FROM_ADDRESS,
                SmtpDeliveryProperties.CREDENTIAL_SECRET_REF,
                SmtpDeliveryProperties.CONNECT_TIMEOUT_MS,
                SmtpDeliveryProperties.READ_TIMEOUT_MS,
                SmtpDeliveryProperties.WRITE_TIMEOUT_MS,
                SmtpDeliveryProperties.STATUS_TABLE_SIZE,
            )
    }
}
