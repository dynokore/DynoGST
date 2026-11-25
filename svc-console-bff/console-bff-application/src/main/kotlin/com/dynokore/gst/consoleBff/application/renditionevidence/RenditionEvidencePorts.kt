package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import java.time.Instant

/** One record of the attestation history held by tenancy-svc; [attestedBy] is the acting user ID, never a role. */
data class AttestationRecord(
    val documentClass: String,
    val renderer: String,
    val attestedBy: String,
    val attestedAt: Instant,
    val sequence: Long,
)

/** One stored SAMPLE_CHECK artefact held by evidence-svc. */
data class SampleCheckArtefactRecord(
    val artefactType: String,
    val reference: String,
    val hash: String,
    val recordedAt: Instant,
)

/** One page of an owner's list (API-18). [nextCursor] is the owner's opaque cursor, null on the last page. */
data class EvidencePage<out T>(
    val items: List<T>,
    val nextCursor: String?,
)

/** The answer of an owner read. A refusal carries the owner's error envelope unchanged (RFC 9457). */
sealed interface PageReply<out T> {
    data class Ok<out T>(
        val page: EvidencePage<T>,
    ) : PageReply<T>

    data class Forbidden(
        val refusal: OwnerRefusal,
    ) : PageReply<Nothing>

    data class NotFound(
        val refusal: OwnerRefusal,
    ) : PageReply<Nothing>

    data class Invalid(
        val refusal: OwnerRefusal,
    ) : PageReply<Nothing>

    /** No answer, or one that cannot be read. */
    data class Unavailable(
        val reason: String,
    ) : PageReply<Nothing>
}

/** listRenditionAttestations of tenancy-svc. No exception crosses this port. */
fun interface AttestationRead {
    fun page(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): PageReply<AttestationRecord>
}

/** listRenditionSampleChecks of evidence-svc. No exception crosses this port. */
fun interface SampleCheckRead {
    fun page(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): PageReply<SampleCheckArtefactRecord>
}
