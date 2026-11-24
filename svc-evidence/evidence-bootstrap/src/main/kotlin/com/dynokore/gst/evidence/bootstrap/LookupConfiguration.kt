package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.SampleCheckCursor
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAcknowledgementLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqArtefactStore
import com.dynokore.gst.evidence.adapters.persistence.JooqDecisionTraceReader
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexReader
import com.dynokore.gst.evidence.application.artefact.ListRenditionSampleChecks
import com.dynokore.gst.evidence.application.artefact.SampleCheckCursors
import com.dynokore.gst.evidence.application.artefact.SampleCheckPage
import com.dynokore.gst.evidence.application.artefact.SampleCheckPosition
import com.dynokore.gst.evidence.application.artefact.SampleCheckReader
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.DecisionTraceLookup
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionLookup
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionReader
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The beans of the lookup operations (INV-REQ-691): the audited-read wrapper over the platform [AuditedOperations]
 * bean and the use case of `getAcknowledgementChain` over the jOOQ locator store, the chain record reader that
 * [ChainWiring.chainRecordReader] builds (the version-column rule stays in one place) and the integrity status query
 * of [ChainVerificationConfiguration]. `reverseLookup` reuses these beans.
 */
@Configuration(proxyBeanMethods = false)
class LookupConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuditedReads::class)
    fun auditedReads(operations: AuditedOperations): AuditedReads = AuditedReads(operations)

    @Bean
    fun acknowledgementChainLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
        integrityStatusQuery: IntegrityStatusQueryHandler,
        audited: AuditedReads,
    ): AcknowledgementChainLookup =
        AcknowledgementChainLookup(
            replica,
            JooqAcknowledgementLocatorStore(dsl),
            ChainWiring.chainRecordReader(dsl),
            integrityStatusQuery,
            audited,
        )

    @Bean
    fun reverseLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
        integrityStatusQuery: IntegrityStatusQueryHandler,
        audited: AuditedReads,
    ): ReverseLookup = ReverseLookup(replica, JooqSubmissionIndexReader(dsl), integrityStatusQuery, audited)

    @Bean
    fun decisionTraceLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
    ): DecisionTraceLookup = DecisionTraceLookup(replica, JooqDecisionTraceReader(dsl))

    @Bean
    fun transmittedVersionLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
        transactions: TransactionRunner,
    ): TransmittedVersionLookup {
        val reader = ChainWiring.transmittedVersionReader(dsl)
        // the permission is decided first; the read of the store (RLS, ARC-21) runs in its own tenant transaction
        return TransmittedVersionLookup(
            replica,
            TransmittedVersionReader { tenant, docUuid, version ->
                transactions.inTransaction { reader.read(tenant, docUuid, version) }
            },
        )
    }

    @Bean
    fun sampleCheckCursors(secrets: SecretStore): SampleCheckCursors = SampleCheckCursor(secrets)

    @Bean
    fun listRenditionSampleChecks(
        replica: AuthorityReplica,
        dsl: DSLContext,
        objects: ObjectStore,
        cursors: SampleCheckCursors,
        clock: Clock,
        transactions: TransactionRunner,
    ): ListRenditionSampleChecks {
        val store = JooqArtefactStore(dsl, objects)
        // the permission is decided first; the read of the store (RLS, ARC-21) runs in its own tenant transaction
        val reader =
            object : SampleCheckReader {
                override fun page(
                    tenant: TenantId,
                    entityRef: String,
                    limit: Int,
                    after: SampleCheckPosition?,
                ): SampleCheckPage = transactions.inTransaction { store.page(tenant, entityRef, limit, after) }
            }
        return ListRenditionSampleChecks(replica, reader, cursors, clock)
    }
}
