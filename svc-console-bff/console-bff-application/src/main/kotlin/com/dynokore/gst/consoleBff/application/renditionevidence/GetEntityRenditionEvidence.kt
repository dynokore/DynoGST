package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal

/** The composed answer of getEntityRenditionEvidence: both pages, or no page at all. */
sealed interface RenditionEvidenceResult {
    data class Composed(
        val attestations: EvidencePage<AttestationRecord>,
        val sampleChecks: EvidencePage<SampleCheckArtefactRecord>,
    ) : RenditionEvidenceResult

    data class Forbidden(
        val refusal: OwnerRefusal,
    ) : RenditionEvidenceResult

    data class NotFound(
        val refusal: OwnerRefusal,
    ) : RenditionEvidenceResult

    data class Invalid(
        val refusal: OwnerRefusal,
    ) : RenditionEvidenceResult

    data class Unavailable(
        val reason: String,
    ) : RenditionEvidenceResult
}

/**
 * INV-REQ-1036: one page of the attestation history and one page of the sample checks of one entity. Each owner is
 * asked once, with the same limit and its own cursor; there is no join across pages and nothing is stored. It fails
 * closed: an unavailable owner gives [RenditionEvidenceResult.Unavailable] and no partial page. When both owners
 * answer and refuse differently, the order is Forbidden, NotFound, Invalid (deny wins, and a refused caller learns
 * nothing about existence: OWASP ASVS V4.1). When both refuse with the same kind, the envelope of the attestation
 * read is relayed. The permission is decided by the read filter, not here.
 */
class GetEntityRenditionEvidence(
    private val attestations: AttestationRead,
    private val sampleChecks: SampleCheckRead,
) {
    fun get(
        entityRef: String,
        limit: Int,
        attestationsCursor: String?,
        sampleChecksCursor: String?,
    ): RenditionEvidenceResult {
        val attested = attestations.page(entityRef, limit, attestationsCursor)
        val checked = sampleChecks.page(entityRef, limit, sampleChecksCursor)
        return if (attested is PageReply.Ok && checked is PageReply.Ok) {
            RenditionEvidenceResult.Composed(attested.page, checked.page)
        } else {
            failure(listOf(attested, checked))
        }
    }

    private fun failure(replies: List<PageReply<Any>>): RenditionEvidenceResult {
        val unavailable = replies.filterIsInstance<PageReply.Unavailable>().firstOrNull()
        val forbidden = replies.filterIsInstance<PageReply.Forbidden>().firstOrNull()
        val notFound = replies.filterIsInstance<PageReply.NotFound>().firstOrNull()
        return when {
            unavailable != null -> RenditionEvidenceResult.Unavailable(unavailable.reason)
            forbidden != null -> RenditionEvidenceResult.Forbidden(forbidden.refusal)
            notFound != null -> RenditionEvidenceResult.NotFound(notFound.refusal)
            else -> RenditionEvidenceResult.Invalid(replies.filterIsInstance<PageReply.Invalid>().first().refusal)
        }
    }
}
