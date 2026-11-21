package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqBoundaryVersionSource
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.application.boundary.CheckSubmissionBoundary
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `inv.boundary-check.*` of application.yaml: the cap has no default in code. */
@ConfigurationProperties("inv.boundary-check")
data class BoundaryProperties(
    val maxDocuments: Int,
) {
    init {
        // Spring Boot binds an absent Int as 0, so a missing key and a 0 both fail the start.
        require(maxDocuments > 0) { "inv.boundary-check.max-documents must be greater than 0" }
    }
}

/** TPL-18 wiring of checkSubmissionBoundary (INV-REQ-1005): the version reader and the use case with the cap. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BoundaryProperties::class)
class BoundaryConfiguration {
    @Bean
    fun boundaryVersionSource(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): BoundaryVersionSource = JooqBoundaryVersionSource(dsl, runner)

    @Bean
    fun checkSubmissionBoundary(
        replica: AuthorityReplica,
        source: BoundaryVersionSource,
        properties: BoundaryProperties,
    ): CheckSubmissionBoundary = CheckSubmissionBoundary(replica, source, properties.maxDocuments)
}
