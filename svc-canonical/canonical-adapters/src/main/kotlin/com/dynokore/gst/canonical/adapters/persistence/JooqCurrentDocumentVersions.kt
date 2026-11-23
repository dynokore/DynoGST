package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.reprocessing.CurrentDocumentVersion
import com.dynokore.gst.canonical.application.reprocessing.CurrentDocumentVersions
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only, read-only, in the caller's open `canonical` transaction. [read] names the tenant in every
 * predicate and answers null for a document that is not stored for that tenant (a document of another tenant is
 * unknown), and for a tenant other than the one the session is bound to. The version is the document's current one
 * (`document.current_version`); its facts come from the stored rows by the pattern of
 * [JooqNetDerivationDocuments.eventBasisOf]. A stored document whose event facts are missing throws (the page rolls
 * back and the event is redelivered): no invented value.
 */
class JooqCurrentDocumentVersions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : CurrentDocumentVersions {
    private val documents = JooqNetDerivationDocuments(dsl, tenants)

    override fun read(
        tenantId: UUID,
        docUuid: UUID,
    ): CurrentDocumentVersion? {
        val version = if (tenants.current().value == tenantId) documents.currentVersion(docUuid) else null
        val content = version?.let { documents.versionAsBase(docUuid, it, createdByOf(tenantId, docUuid, it)) }
        if (version == null || content == null) return null
        return CurrentDocumentVersion(version, content, facts(tenantId, docUuid, version, content))
    }

    private fun createdByOf(
        tenantId: UUID,
        docUuid: UUID,
        version: Int,
    ): String =
        checkNotNull(
            dsl
                .select(DOCUMENT_VERSION.CREATED_BY)
                .from(DOCUMENT_VERSION)
                .join(DOCUMENT)
                .on(DOCUMENT_VERSION.DOC_UUID.eq(DOCUMENT.DOC_UUID))
                .and(DOCUMENT_VERSION.PARTITION_DATE.eq(DOCUMENT.PARTITION_DATE))
                .and(DOCUMENT_VERSION.TENANT_ID.eq(DOCUMENT.TENANT_ID))
                .where(DOCUMENT.TENANT_ID.eq(tenantId))
                .and(DOCUMENT.DOC_UUID.eq(docUuid))
                .and(DOCUMENT_VERSION.VERSION.eq(version))
                .fetchOne(DOCUMENT_VERSION.CREATED_BY),
        ) { "$docUuid v$version has no creator" }

    private fun facts(
        tenantId: UUID,
        docUuid: UUID,
        version: Int,
        content: NewVersion,
    ): CanonicalVersionCreatedFacts {
        val basis =
            checkNotNull(documents.eventBasisOf(docUuid, version)) { "$docUuid v$version has no stored event facts" }
        val aggregateId =
            dsl
                .select(DOCUMENT.AGGREGATE_ID)
                .from(DOCUMENT)
                .where(DOCUMENT.TENANT_ID.eq(tenantId))
                .and(DOCUMENT.DOC_UUID.eq(docUuid))
                .fetchOne(DOCUMENT.AGGREGATE_ID)
        return CanonicalVersionCreatedFacts(
            docUuid = docUuid,
            entityRef = basis.entityRef,
            version = version,
            transactionDate = content.transactionDate,
            prescribedPeriodRef = basis.prescribedPeriodRef,
            scopeVerdict = basis.scopeVerdict,
            appliedPackVersions = checkNotNull(documents.appliedPacksOf(docUuid, version)),
            traceReference = "trace/$docUuid/v$version",
            canonicalDocument = content.canonical,
            aggregateId = aggregateId,
            routingFacts =
                RoutingFactsData(
                    direction = RoutingDirection.valueOf(basis.direction),
                    networkPath =
                        if (basis.receivedViaNetwork) {
                            RoutingNetworkPath.RECEIVED_VIA_NETWORK
                        } else {
                            RoutingNetworkPath.NOT_ON_NETWORK
                        },
                ),
        )
    }
}
