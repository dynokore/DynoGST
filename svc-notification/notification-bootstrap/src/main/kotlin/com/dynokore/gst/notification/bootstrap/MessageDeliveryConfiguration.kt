package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.HttpsMailApiDeliveryProperties
import com.dynokore.gst.notification.adapters.delivery.HttpsMailApiMessageDelivery
import com.dynokore.gst.notification.adapters.delivery.SmtpDeliveryProperties
import com.dynokore.gst.notification.adapters.delivery.SmtpMessageDelivery
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
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
 *
 * The mail API adapter is bound the same way, to the same EMAIL channel, only when
 * `gst.message-delivery.email.api.endpoint-url` is set; then every other key of its set is required (a missing or
 * malformed one, including an endpoint that is not https, stops the start naming the key only); other API keys
 * without the endpoint also stop the start, naming the endpoint key. Exactly one EMAIL
 * implementation may be configured: the endpoint key and the SMTP host key together stop the start before any
 * delivery bean is built, naming the two keys and no value (fail closed on an ambiguous binding).
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
    @ConditionalOnProperty(name = [HttpsMailApiDeliveryProperties.ENDPOINT_URL])
    fun emailMailApiMessageDelivery(
        environment: Environment,
        secrets: SecretStore,
    ): MessageDelivery {
        val properties = HttpsMailApiDeliveryProperties.from(environment::getProperty)
        return HttpsMailApiMessageDelivery(
            properties,
            HttpsMailApiMessageDelivery.productionClient(properties),
            secrets,
        )
    }

    @Bean
    fun smtpKeySetCheck(environment: Environment): SmtpKeySetCheck {
        val others = KEYS.filter { it != SmtpDeliveryProperties.HOST && environment.containsProperty(it) }
        check(environment.containsProperty(SmtpDeliveryProperties.HOST) || others.isEmpty()) {
            "missing configuration key ${SmtpDeliveryProperties.HOST}"
        }
        return SmtpKeySetCheck
    }

    @Bean
    fun mailApiKeySetCheck(environment: Environment): MailApiKeySetCheck {
        val others =
            API_KEYS.filter {
                it != HttpsMailApiDeliveryProperties.ENDPOINT_URL &&
                    environment.containsProperty(it)
            }
        check(environment.containsProperty(HttpsMailApiDeliveryProperties.ENDPOINT_URL) || others.isEmpty()) {
            "missing configuration key ${HttpsMailApiDeliveryProperties.ENDPOINT_URL}"
        }
        return MailApiKeySetCheck
    }

    /** Marker of a passed check; the bean exists only so that the check runs at start. */
    object MailApiKeySetCheck

    /** Marker of a passed check; the bean exists only so that the check runs at start. */
    object SmtpKeySetCheck

    companion object {
        /** Runs before any bean is built, so the ambiguity is reported whatever else is configured. */
        @Bean
        @JvmStatic
        fun emailExclusivityCheck(environment: Environment): BeanFactoryPostProcessor =
            BeanFactoryPostProcessor {
                check(
                    !(
                        environment.containsProperty(HttpsMailApiDeliveryProperties.ENDPOINT_URL) &&
                            environment.containsProperty(SmtpDeliveryProperties.HOST)
                    ),
                ) {
                    "exactly one EMAIL implementation may be configured: " +
                        "${HttpsMailApiDeliveryProperties.ENDPOINT_URL} and ${SmtpDeliveryProperties.HOST} are both set"
                }
            }

        private val API_KEYS =
            listOf(
                HttpsMailApiDeliveryProperties.ENDPOINT_URL,
                HttpsMailApiDeliveryProperties.FROM_ADDRESS,
                HttpsMailApiDeliveryProperties.CREDENTIAL_SECRET_REF,
                HttpsMailApiDeliveryProperties.CONNECT_TIMEOUT_MS,
                HttpsMailApiDeliveryProperties.REQUEST_TIMEOUT_MS,
                HttpsMailApiDeliveryProperties.STATUS_TABLE_SIZE,
            )

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
