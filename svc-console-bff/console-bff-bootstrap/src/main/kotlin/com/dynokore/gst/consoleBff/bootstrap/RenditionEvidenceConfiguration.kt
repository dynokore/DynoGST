package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.clients.AttestationReadClient
import com.dynokore.gst.consoleBff.adapters.clients.SampleCheckReadClient
import com.dynokore.gst.consoleBff.application.renditionevidence.GetEntityRenditionEvidence
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * getEntityRenditionEvidence (INV-REQ-1036): the use case over the tenancy-svc and evidence-svc read clients and its
 * controller. The two clients are not component-scanned, so they are imported here; their base URLs
 * `console.tenancy-svc-url` and `console.evidence-svc-url` are required properties with no default (12-factor).
 */
@Configuration(proxyBeanMethods = false)
@Import(AttestationReadClient::class, SampleCheckReadClient::class)
class RenditionEvidenceConfiguration {
    @Bean
    fun getEntityRenditionEvidence(
        attestations: AttestationReadClient,
        sampleChecks: SampleCheckReadClient,
    ) = GetEntityRenditionEvidence(attestations, sampleChecks)

    @Bean
    fun renditionEvidenceController(
        getEntityRenditionEvidence: GetEntityRenditionEvidence,
        overridesProblemMapper: OverridesProblemMapper,
    ) = RenditionEvidenceController(getEntityRenditionEvidence, overridesProblemMapper)
}
