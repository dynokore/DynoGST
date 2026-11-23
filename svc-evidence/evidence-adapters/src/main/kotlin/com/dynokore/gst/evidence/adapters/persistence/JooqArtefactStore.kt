package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidenceArtefact.Companion.EVIDENCE_ARTEFACT
import com.dynokore.gst.evidence.application.artefact.ArtefactStore
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * `evidence_artefact` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3).
 *
 * A RECONCILIATION_WORK_PAPER row records `period_ref` (INV-AC-28217): the sealed object under the event's reference
 * is read from [objects], its SHA-256 is compared with the event's hash and `header.periodRef` is stored. A read
 * failure propagates, so nothing is stored and the inbox consumer retries the delivery; bytes that do not match the
 * hash, or an absent or blank `header.periodRef`, raise [ContractViolation] and store nothing. A row of any other
 * type keeps `period_ref` null and reads no object.
 */
class JooqArtefactStore(
    private val dsl: DSLContext,
    private val objects: ObjectStore,
) : ArtefactStore {
    override fun insert(
        tenant: TenantId,
        entityRef: String?,
        artefact: EvidenceArtefact,
        eventId: String,
        recordedAt: Instant,
    ): Boolean {
        val period = if (artefact.type.value == WORK_PAPER) periodOf(artefact) else null
        return dsl
            .insertInto(EVIDENCE_ARTEFACT)
            .set(EVIDENCE_ARTEFACT.ID, UUID.randomUUID())
            .set(EVIDENCE_ARTEFACT.TENANT_ID, tenant.value)
            .set(EVIDENCE_ARTEFACT.ENTITY_REF, entityRef)
            .set(EVIDENCE_ARTEFACT.ARTEFACT_TYPE, artefact.type.value)
            .set(EVIDENCE_ARTEFACT.REFERENCE, artefact.reference)
            .set(EVIDENCE_ARTEFACT.HASH, artefact.hash)
            .set(EVIDENCE_ARTEFACT.EVENT_ID, eventId)
            .set(EVIDENCE_ARTEFACT.RECORDED_AT, recordedAt.utc())
            .set(EVIDENCE_ARTEFACT.PERIOD_REF, period)
            .onConflict(
                EVIDENCE_ARTEFACT.TENANT_ID,
                EVIDENCE_ARTEFACT.ARTEFACT_TYPE,
                EVIDENCE_ARTEFACT.REFERENCE,
                EVIDENCE_ARTEFACT.HASH,
            ).doNothing()
            .execute() == 1
    }

    private fun periodOf(artefact: EvidenceArtefact): String {
        val bytes = objects.get(ObjectKey(artefact.reference))
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val period = if (digest == artefact.hash) headerPeriod(bytes, artefact.reference) else null
        return period ?: throw ContractViolation("the sealed work-paper ${artefact.reference} has no verified period")
    }

    /** `header.periodRef` of the sealed bytes; null when absent or blank, [ContractViolation] when not JSON. */
    private fun headerPeriod(
        bytes: ByteArray,
        reference: String,
    ): String? {
        val node =
            try {
                MAPPER.readTree(bytes).path("header").path("periodRef")
            } catch (e: JacksonException) {
                throw ContractViolation("the sealed work-paper $reference is not JSON", e)
            }
        return if (node.isString) node.asString().takeIf { it.isNotBlank() } else null
    }

    private companion object {
        const val WORK_PAPER = "RECONCILIATION_WORK_PAPER"
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
