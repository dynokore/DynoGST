package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.inbound.InboundEventStore
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.inbound.InboundEvent
import com.dynokore.gst.canonical.domain.inbound.InboundKind
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import java.util.UUID

/** The next version could not be written because the current-version pointer moved; the record fails closed. */
class DocumentRowConflict(
    val docUuid: UUID,
    val expectedVersion: Int,
) : IllegalStateException("document row conflict for $docUuid at version $expectedVersion")

/**
 * Writes the row of a record only through the single version writer, so `CanonicalVersionCreated` is staged in the
 * same transaction as the version (transactional outbox). Every version also appends its `inbound_event` row through
 * [inbound] (kind POSTING, the asserted document function unchanged), in the caller's transaction, so both roll back
 * together. A network receipt is refused before any write: the receipt fields are not on the command.
 * A version without a scope verdict or without a prescribed period is written but not announced; a later version
 * announces. After an ERP version above 1 it calls [rederivation] in the same transaction (INV-REQ-558 behavior 6)
 * with the credit notes the ERP declared on the record, resolved by [declaredCreditNotes].
 */
class DocumentRowWriter(
    private val writer: CanonicalVersionWriter,
    private val rederivation: NetRederivation,
    private val declaredCreditNotes: DeclaredCreditNotes,
    private val inbound: InboundEventStore,
) {
    fun write(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        facts: DocumentRowFacts,
    ) {
        check(command.networkPath != RoutingNetworkPath.RECEIVED_VIA_NETWORK) {
            "a network receipt is recorded as a RECEIPT row, not by the record path"
        }
        val reason = unannouncedReason(facts)
        val link =
            SourceLink.Recorded(
                SourceKey(command.tenantId, command.sourceSystem, command.sourceKey, command.sourceRevision),
            )
        if (version == 1) {
            val document = newDocument(command, docUuid, facts)
            if (reason == null) {
                writer.createFirst(document, eventOf(command, docUuid, version, facts), link)
            } else {
                writer.createFirstUnannounced(document, reason, link)
            }
            inbound.append(inboundEventOf(command, facts), docUuid, version)
            return
        }
        val expected = version - 1
        val outcome =
            if (reason == null) {
                writer.next(docUuid, expected, facts.version, eventOf(command, docUuid, version, facts), link)
            } else {
                writer.nextUnannounced(docUuid, expected, facts.version, reason, link)
            }
        if (outcome is UpdateOutcome.Conflict) throw DocumentRowConflict(docUuid, expected)
        inbound.append(inboundEventOf(command, facts), docUuid, version)
        rederivation.afterErpVersion(docUuid, version, declaredCreditNotes.of(command, facts.entityId), link)
    }

    private fun inboundEventOf(
        command: RecordDocumentCommand,
        facts: DocumentRowFacts,
    ): InboundEvent {
        val purchase = checkNotNull(command.direction) { "direction is absent" } == Direction.PURCHASE
        return InboundEvent(
            kind = InboundKind.POSTING,
            entityId = facts.entityId,
            documentFunction = checkNotNull(command.documentFunction) { "documentFunction is absent" },
            supplierScheme = if (purchase) command.supplier.scheme else "",
            supplierValue = if (purchase) command.supplier.value else "",
            documentNumber = checkNotNull(command.documentNumber) { "documentNumber is absent" },
            transactionDate = facts.version.transactionDate,
        )
    }

    private fun unannouncedReason(facts: DocumentRowFacts): UnannouncedReason? =
        when {
            facts.version.scopeVerdict == null -> UnannouncedReason.NO_SCOPE_VERDICT
            facts.prescribedPeriodRef.isEmpty() -> UnannouncedReason.NO_PRESCRIBED_PERIOD
            else -> null
        }

    private fun newDocument(
        command: RecordDocumentCommand,
        docUuid: UUID,
        facts: DocumentRowFacts,
    ): NewDocument =
        NewDocument(
            docUuid = docUuid,
            tenantId = command.tenantId,
            entityId = facts.entityId,
            businessUnitId = facts.businessUnitId,
            direction = checkNotNull(command.direction) { "direction is absent" },
            documentTypeCode = facts.documentTypeCode,
            documentNumber = checkNotNull(command.documentNumber) { "documentNumber is absent" },
            issuerPartyKey = facts.issuerPartyKey,
            fiscalYear = facts.fiscalYear,
            internalReference = facts.internalReference,
            state = facts.state,
            prescribedPeriodId = facts.prescribedPeriodId,
            first = facts.version,
            entityRef = command.entityRef,
        )

    private fun eventOf(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        facts: DocumentRowFacts,
    ): CanonicalVersionCreatedFacts =
        CanonicalVersionCreatedFacts(
            docUuid = docUuid,
            entityRef = command.entityRef,
            version = version,
            transactionDate = facts.version.transactionDate,
            prescribedPeriodRef = facts.prescribedPeriodRef,
            scopeVerdict = checkNotNull(facts.version.scopeVerdict).name,
            appliedPackVersions =
                facts.appliedPackVersions.map { AppliedPackVersion(it.packIdentifier, it.version, it.packType) },
            traceReference = "trace/$docUuid/v$version",
            canonicalDocument = facts.version.canonical,
            aggregateId = null,
            routingFacts = routingOf(command),
        )

    private fun routingOf(command: RecordDocumentCommand): RoutingFactsData =
        RoutingFactsData(
            direction = RoutingDirection.valueOf(checkNotNull(command.direction) { "direction is absent" }.name),
            networkPath = command.networkPath,
            debitNote = true.takeIf { command.documentFunction == DocumentFunction.DEBIT_NOTE },
        )
}
