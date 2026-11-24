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
 * the key only, never a value. With the host absent no `MessageDelivery` bean exists and the delivery chain records
 * FAILED CHANNEL_NOT_BOUND (fail closed). No default exists in code.
 */
@Configuration(proxyBeanMethods = false)
class MessageDeliveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = [SmtpDeliveryProperties.HOST])
    fun emailMessageDelivery(
        environment: Environment,
        secrets: SecretStore,
    ): MessageDelivery = SmtpMessageDelivery(SmtpDeliveryProperties.from(environment::getProperty), secrets)
}
