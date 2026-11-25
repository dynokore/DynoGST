package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.document.SourceLink
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.NetDerivation
import com.dynokore.gst.canonical.domain.netting.NetRefusal
import com.dynokore.gst.canonical.domain.netting.Refused
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import java.util.UUID

/**
 * Answers one net derivation request (INV-REQ-558 behavior 6): a new version of the original written only through
 * [CanonicalVersionWriter], or a refusal event. Idempotent by the request's event id. It opens no transaction: the
 * caller runs [handle] in one, so a failing port rolls the processed-request mark back. No exception is swallowed.
 */
@Suppress("LongParameterList") // composition root: one handler over its ports and the facts seam
class NetDerivationRequestHandler(
    private val requests: ProcessedNetDerivationRequests,
    private val documents: NetDerivationDocuments,
    private val versions: NetDerivedVersions,
    private val writer: CanonicalVersionWriter,
    private val records: NetDerivationRecords,
    private val refusals: NetDerivationRefusedPublisher,
    private val factsFor: (originalDocUuid: UUID, netDerivation: NetDerivationFact) -> CanonicalVersionCreatedFacts,
) {
    fun handle(
        requestEventId: UUID,
        originalDocUuid: UUID,
        expectedBaseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
    ) {
        if (!requests.markIfNew(requestEventId)) return
        val link = SourceLink.Consumed(REQUEST_SOURCE_SYSTEM, requestEventId.toString())
        val reason = process(originalDocUuid, expectedBaseVersion, creditNotes, link)
        if (reason != null) refusals.stage(originalDocUuid, requestEventId, reason, creditNotes)
    }

    /** The refusal reason, or null when the new version was written. */
    private fun process(
        originalDocUuid: UUID,
        expectedBaseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
        link: SourceLink,
    ): NetRefusal? {
        val current = documents.currentVersion(originalDocUuid)
        return when {
            current == null -> NetRefusal.DOCUMENT_NOT_FOUND
            current != expectedBaseVersion -> NetRefusal.BASE_CHANGED
            else -> derive(originalDocUuid, expectedBaseVersion, creditNotes, link)
        }
    }

    private fun derive(
        originalDocUuid: UUID,
        expectedBaseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
        link: SourceLink,
    ): NetRefusal? {
        val base = documents.base(originalDocUuid, expectedBaseVersion)
        val notes = creditNotes.map { documents.creditNote(it.docUuid, it.version) }
        if (base == null || notes.any { it == null }) return NetRefusal.DOCUMENT_NOT_FOUND
        return when (val result = NetDerivation.derive(originalDocUuid, base, notes.filterNotNull())) {
            is Refused -> result.reason
            is Derived -> write(originalDocUuid, expectedBaseVersion, creditNotes, result, link)
        }
    }

    private fun write(
        originalDocUuid: UUID,
        expectedBaseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
        derived: Derived,
        link: SourceLink,
    ): NetRefusal? {
        val fact = NetDerivationFact(baseVersion = expectedBaseVersion, creditNotes = creditNotes)
        val outcome =
            writer.next(
                originalDocUuid,
                expectedBaseVersion,
                versions.build(originalDocUuid, expectedBaseVersion, derived),
                factsFor(originalDocUuid, fact),
                link,
            )
        if (outcome !is UpdateOutcome.Updated) return NetRefusal.BASE_CHANGED
        records.record(originalDocUuid, outcome.version.toInt(), expectedBaseVersion, creditNotes)
        return null
    }

    private companion object {
        /** The only producer of the request event (event-log/topics.yaml of platform-contracts). */
        const val REQUEST_SOURCE_SYSTEM = "submission-svc"
    }
}
