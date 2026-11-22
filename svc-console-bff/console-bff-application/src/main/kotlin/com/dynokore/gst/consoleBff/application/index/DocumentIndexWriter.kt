package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldReader
import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedReader
import com.dynokore.gst.consoleBff.domain.index.DocumentIndexRow
import com.dynokore.gst.consoleBff.domain.index.IndexState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

private const val SUBMITTABLE = "SUBMITTABLE"

private fun outcome(changed: Boolean) = if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED

private fun TenantContext.tenant(): String = current().value.toString()

private fun Envelope.entityRefOrNull(): String? = if (entity.isTenantWide || entity.isPlatform) null else entity.value

private fun DocumentIndexRow.moveTo(target: IndexState): DocumentIndexRow =
    if (target.rank > state.rank) copy(state = target) else this

/**
 * CanonicalVersionCreated creates the row (tenant, docUuid, version) with READY when scopeVerdict is SUBMITTABLE, else
 * NOT_SUBMITTABLE. An existing key is left unchanged (events are at-least-once). A payload without `entityRef` writes
 * no row, counts `unscopedVersionEvent()` once and is IGNORED (fail closed). Runs in the consumer's transaction.
 */
class CanonicalVersionCreatedHandler(
    private val tenants: TenantContext,
    private val reader: CanonicalVersionCreatedReader,
    private val store: DocumentIndexStore,
    private val metrics: IndexMetrics,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val entityRef = facts.entityRef
        if (entityRef.isNullOrBlank()) {
            metrics.unscopedVersionEvent()
            return HandleOutcome.IGNORED
        }
        val row =
            DocumentIndexRow(
                tenantId = tenants.tenant(),
                docUuid = facts.docUuid,
                version = facts.version,
                entityRef = entityRef,
                documentNumber = facts.documentNumber,
                counterpartyName = facts.counterpartyName,
                counterpartyUen = facts.counterpartyUen,
                amount = facts.amount,
                documentDate = facts.documentDate,
                categoryCodes = facts.categoryCodes,
                state = if (facts.scopeVerdict == SUBMITTABLE) IndexState.READY else IndexState.NOT_SUBMITTABLE,
                acknowledgementId = null,
                errorCode = null,
                transactionDate = facts.transactionDate,
            )
        return outcome(store.upsert(row))
    }
}

/** DocumentHeld sets HELD on the rows of the Doc UUID that are READY or NOT_SUBMITTABLE; it never creates a row. */
class IndexDocumentHeldHandler(
    private val tenants: TenantContext,
    private val reader: DocumentHeldReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson, event.entityRefOrNull())
        val changed =
            store.updateDocument(tenants.tenant(), facts.docUuid) {
                if (it.state == IndexState.READY ||
                    it.state == IndexState.NOT_SUBMITTABLE
                ) {
                    it.copy(state = IndexState.HELD)
                } else {
                    it
                }
            }
        return outcome(changed > 0)
    }
}

/** DocumentReleased sets READY on the rows of the Doc UUID that are HELD. */
class IndexDocumentReleasedHandler(
    private val tenants: TenantContext,
    private val reader: DocumentReleasedReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val changed =
            store.updateDocument(tenants.tenant(), facts.docUuid) {
                if (it.state == IndexState.HELD) it.copy(state = IndexState.READY) else it
            }
        return outcome(changed > 0)
    }
}

/** SubmissionDespatched sets SENT on the row of the version, never lowering a higher rank. No row means no change. */
class IndexSubmissionDespatchedHandler(
    private val tenants: TenantContext,
    private val reader: SubmissionDespatchedReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        return outcome(store.update(tenants.tenant(), facts.docUuid, facts.version) { it.moveTo(IndexState.SENT) } > 0)
    }
}

/** SubmissionAcknowledged sets ACKNOWLEDGED and the acknowledgement identifier on the row of the version. */
class IndexSubmissionAcknowledgedHandler(
    private val tenants: TenantContext,
    private val reader: SubmissionAcknowledgedReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val changed =
            store.update(tenants.tenant(), facts.docUuid, facts.version) {
                it.moveTo(IndexState.ACKNOWLEDGED).copy(
                    acknowledgementId =
                        facts.acknowledgementId ?: it.acknowledgementId,
                )
            }
        return outcome(changed > 0)
    }
}

/**
 * SubmissionFailed sets error_code from the member `code` on the row of the payload's `version` and changes no state;
 * a payload without `code` changes nothing.
 */
class IndexSubmissionFailedHandler(
    private val tenants: TenantContext,
    private val reader: SubmissionFailedIndexReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val code = facts.code ?: return HandleOutcome.IGNORED
        return outcome(store.update(tenants.tenant(), facts.docUuid, facts.version) { it.copy(errorCode = code) } > 0)
    }
}

/** MasterDataResolved sets counterparty_uen on the rows of the listed Doc UUIDs; ignored without docUuids or a UEN. */
class IndexMasterDataResolvedHandler(
    private val tenants: TenantContext,
    private val reader: MasterDataResolvedUenReader,
    private val store: DocumentIndexStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val uen = facts.uen
        if (facts.docUuids.isEmpty() || uen == null) return HandleOutcome.IGNORED
        val tenant = tenants.tenant()
        var changed = 0
        for (docUuid in facts.docUuids.distinct()) {
            changed += store.updateDocument(tenant, docUuid) { it.copy(counterpartyUen = uen) }
        }
        return outcome(changed > 0)
    }
}
