package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.RsaOaepPasscodeOpener
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The passcode opener of the delivery use case (INV-REQ-2762): RSAES-OAEP over the delivery private key read through
 * the `SecretStore` binding. The opener reads the secret on first use, never at start-up, so a context without the
 * secret starts; a passcode message then fails closed (FAILED PASSCODE_UNOPENABLE) and a link message is unaffected.
 */
@Configuration(proxyBeanMethods = false)
class PasscodeOpenerConfiguration {
    @Bean
    fun passcodeOpener(secrets: SecretStore): RsaOaepPasscodeOpener = RsaOaepPasscodeOpener(secrets)
}
