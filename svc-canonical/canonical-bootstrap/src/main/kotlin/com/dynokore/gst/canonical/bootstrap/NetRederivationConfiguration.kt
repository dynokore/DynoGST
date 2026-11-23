package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.CapturingNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.JacksonNetDerivedVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqInboundEventStore
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationDocuments
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationRecords
import com.dynokore.gst.canonical.adapters.persistence.JooqRecordedDocumentsByKey
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.inbound.InboundEventStore
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.application.netting.NettingLinksDeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.RecordedDocumentsByKey
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of the netting wiring of the record path (INV-REQ-558 behavior 4 and 6): the lookup of declared source
 * keys, the declared credit notes, the re-derivation after an ERP version and the inbound event store the row writer
 * appends to. The `factsFor` of the re-derivation is [factsOf], the function of the consumer.
 *
 * `CapturingNetDerivedVersions` keeps the text of the last build in a [ThreadLocal], so the one instance below holds
 * its state per thread: the record path runs on concurrent request threads and none sees the text another captured.
 */
@Configuration(proxyBeanMethods = false)
class NetRederivationConfiguration {
    @Bean
    fun recordedDocumentsByKey(
        dsl: DSLContext,
        tenants: TenantContext,
    ): RecordedDocumentsByKey = JooqRecordedDocumentsByKey(dsl, tenants)

    @Bean
    fun declaredCreditNotes(lookup: RecordedDocumentsByKey): DeclaredCreditNotes =
        NettingLinksDeclaredCreditNotes(lookup)

    @Bean
    fun inboundEventStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): InboundEventStore = JooqInboundEventStore(dsl, tenants)

    @Bean
    fun netRederivation(
        dsl: DSLContext,
        tenants: TenantContext,
        documents: JooqNetDerivationDocuments,
        writer: CanonicalVersionWriter,
    ): NetRederivation {
        val versions =
            CapturingNetDerivedVersions(
                JacksonNetDerivedVersions { docUuid, version ->
                    val identity = checkNotNull(tenants.currentActor()) { "no acting identity is bound to the record" }
                    checkNotNull(documents.versionAsBase(docUuid, version, identity)) {
                        "version $version of $docUuid is not stored"
                    }
                },
            )
        return NetRederivation(
            records = JooqNetDerivationRecords(dsl, tenants),
            documents = documents,
            versions = versions,
            writer = writer,
            factsFor = { docUuid, net -> factsOf(documents, versions, docUuid, net) },
        )
    }
}
