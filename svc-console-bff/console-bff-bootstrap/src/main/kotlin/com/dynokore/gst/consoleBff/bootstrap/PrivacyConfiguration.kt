package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.clients.CounterpartyCorrectionClient
import com.dynokore.gst.consoleBff.adapters.clients.EvidenceAnnotationClient
import com.dynokore.gst.consoleBff.adapters.clients.PrivacyCaseClient
import com.dynokore.gst.consoleBff.adapters.privacy.ReplicaPrivacyAuthority
import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalData
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * correctPersonalData (INV-REQ-886): the saga use case over the replica authority and the three owner clients
 * (`PrivacyCaseClient`, `CounterpartyCorrectionClient`, `EvidenceAnnotationClient`, whose base URLs are required
 * properties), and the problem mapper that `PrivacyController` uses.
 */
@Configuration(proxyBeanMethods = false)
class PrivacyConfiguration {
    @Bean
    fun correctPersonalData(
        authorityReplica: AuthorityReplica,
        clock: Clock,
        cases: PrivacyCaseClient,
        corrections: CounterpartyCorrectionClient,
        annotations: EvidenceAnnotationClient,
    ) = CorrectPersonalData(
        ReplicaPrivacyAuthority(authorityReplica, clock, Duration.ofSeconds(1)),
        cases,
        corrections,
        annotations,
    )

    @Bean
    fun privacyProblemMapper() = PrivacyProblemMapper()

    @Bean
    fun privacyController(
        correctPersonalData: CorrectPersonalData,
        privacyProblemMapper: PrivacyProblemMapper,
    ) = PrivacyController(correctPersonalData, privacyProblemMapper)
}
