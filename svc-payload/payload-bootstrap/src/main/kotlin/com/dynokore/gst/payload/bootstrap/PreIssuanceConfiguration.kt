package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.clients.MasterdataRegisterStatusSource
import com.dynokore.gst.payload.application.preissuance.CheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * The pre-issuance check (INV-REQ-475) composed: the register client over masterdata-svc and the use case built from
 * it. The property `payload.masterdata-svc-url` is required (the adapter reads it with `getRequiredProperty` and
 * refuses a blank value), so the context does not start without it.
 */
@Configuration(proxyBeanMethods = false)
class PreIssuanceConfiguration {
    @Bean
    fun registerStatusSource(
        @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
        registry: ResilienceRegistry,
        clock: Clock,
        environment: Environment,
    ): RegisterStatusSource = MasterdataRegisterStatusSource(builder, registry, clock, environment)

    @Bean
    fun checkPreIssuance(register: RegisterStatusSource): CheckPreIssuance = CheckPreIssuance(register)
}
