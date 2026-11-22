package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidenceArtefact.Companion.EVIDENCE_ARTEFACT
import com.dynokore.gst.evidence.application.artefact.ArtefactStore
import com.dynokore.gst.evidence.application.artefact.SampleCheckPage
import com.dynokore.gst.evidence.application.artefact.SampleCheckPosition
import com.dynokore.gst.evidence.application.artefact.SampleCheckReader
import com.dynokore.gst.evidence.application.artefact.SampleCheckRow
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** `evidence_artefact` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqArtefactStore(
    private val dsl: DSLContext,
) : ArtefactStore,
    SampleCheckReader {
    override fun insert(
        tenant: TenantId,
        entityRef: String?,
        artefact: EvidenceArtefact,
        eventId: String,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(EVIDENCE_ARTEFACT)
            .set(EVIDENCE_ARTEFACT.ID, UUID.randomUUID())
            .set(EVIDENCE_ARTEFACT.TENANT_ID, tenant.value)
            .set(EVIDENCE_ARTEFACT.ENTITY_REF, entityRef)
            .set(EVIDENCE_ARTEFACT.ARTEFACT_TYPE, artefact.type.value)
            .set(EVIDENCE_ARTEFACT.REFERENCE, artefact.reference)
            .set(EVIDENCE_ARTEFACT.HASH, artefact.hash)
            .set(EVIDENCE_ARTEFACT.EVENT_ID, eventId)
            .set(EVIDENCE_ARTEFACT.RECORDED_AT, recordedAt.utc())
            .onConflict(
                EVIDENCE_ARTEFACT.TENANT_ID,
                EVIDENCE_ARTEFACT.ARTEFACT_TYPE,
                EVIDENCE_ARTEFACT.REFERENCE,
                EVIDENCE_ARTEFACT.HASH,
            ).doNothing()
            .execute() == 1

    /** Keyset page over (recorded_at, reference, hash); one row more than [limit] is read to know whether more exist. */
    override fun page(
        tenant: TenantId,
        entityRef: String,
        limit: Int,
        after: SampleCheckPosition?,
    ): SampleCheckPage {
        val sortKey = DSL.row(EVIDENCE_ARTEFACT.RECORDED_AT, EVIDENCE_ARTEFACT.REFERENCE, EVIDENCE_ARTEFACT.HASH)
        val rows =
            dsl
                .selectFrom(EVIDENCE_ARTEFACT)
                .where(EVIDENCE_ARTEFACT.TENANT_ID.eq(tenant.value))
                .and(EVIDENCE_ARTEFACT.ENTITY_REF.eq(entityRef))
                .and(EVIDENCE_ARTEFACT.ARTEFACT_TYPE.eq(EvidenceArtefactType.SAMPLE_CHECK.value))
                .and(
                    after?.let { sortKey.gt(DSL.row(it.recordedAt.utc(), it.reference, it.hash)) } ?: DSL.noCondition(),
                ).orderBy(EVIDENCE_ARTEFACT.RECORDED_AT, EVIDENCE_ARTEFACT.REFERENCE, EVIDENCE_ARTEFACT.HASH)
                .limit(limit + 1)
                .fetch()
                .map {
                    SampleCheckRow(
                        checkNotNull(it.artefactType),
                        checkNotNull(it.reference),
                        checkNotNull(it.hash),
                        checkNotNull(it.recordedAt).toInstant(),
                    )
                }
        val page = rows.take(limit)
        return SampleCheckPage(page, if (rows.size > limit) page.last().position else null)
    }
}
