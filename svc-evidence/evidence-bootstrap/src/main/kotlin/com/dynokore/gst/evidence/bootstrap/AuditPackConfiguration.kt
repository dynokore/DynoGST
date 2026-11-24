package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.JooqAuditPackJobs
import com.dynokore.gst.evidence.adapters.persistence.JooqIntegrityStore
import com.dynokore.gst.evidence.adapters.persistence.JooqReconciliationWorkPapers
import com.dynokore.gst.evidence.adapters.persistence.JooqSourceDocumentReferences
import com.dynokore.gst.evidence.adapters.persistence.JooqTenantPackageCandidates
import com.dynokore.gst.evidence.application.export.AuditPackClassSource
import com.dynokore.gst.evidence.application.export.AuditPackClassSources
import com.dynokore.gst.evidence.application.export.AuditPackJobs
import com.dynokore.gst.evidence.application.export.ExportAuditPack
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * The audit pack of one entity and period (INV-REQ-988): the six class sources read with the entity and period
 * predicates, the job store and the use case. The byte limit is the property the tenant export uses
 * ([OffboardingExportConfiguration.MAX_BYTES_PROPERTY]); the package store and the chain gate are the beans of the
 * whole-tenant export, so a missing one fails the context at start-up.
 */
@Configuration(proxyBeanMethods = false)
class AuditPackConfiguration {
    @Bean
    fun auditPackClassSources(
        dsl: DSLContext,
        environment: Environment,
        objectStore: ObjectStore,
    ): AuditPackClassSources {
        val maxBytes =
            environment.getProperty(
                OffboardingExportConfiguration.MAX_BYTES_PROPERTY,
                Long::class.javaObjectType,
                OffboardingExportConfiguration.DEFAULT_MAX_PACKAGE_BYTES,
            )
        val sources: Map<PackageContentClass, AuditPackClassSource> =
            JooqTenantPackageCandidates(dsl, maxBytes).scopedSources() +
                mapOf(
                    PackageContentClass.SOURCE_DOCUMENT_REFERENCES to JooqSourceDocumentReferences(dsl, maxBytes),
                    PackageContentClass.RECONCILIATION_WORK_PAPERS to
                        JooqReconciliationWorkPapers(dsl, objectStore, maxBytes),
                )
        return AuditPackClassSources(sources)
    }

    @Bean
    fun auditPackJobs(
        dsl: DSLContext,
        clock: Clock,
    ): AuditPackJobs = JooqAuditPackJobs(dsl, clock)

    @Bean
    @Suppress("LongParameterList") // the use case and its ports
    fun exportAuditPack(
        sources: AuditPackClassSources,
        jobs: AuditPackJobs,
        store: ExportPackageStore,
        dsl: DSLContext,
        chainIntegrity: ExportChainIntegrity,
        clock: Clock,
    ): ExportAuditPack = ExportAuditPack(sources, jobs, store, JooqIntegrityStore(dsl), chainIntegrity, clock)
}
