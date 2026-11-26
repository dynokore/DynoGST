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

/**
 * The members of a stored payload sample check that the list join uses (INV-REQ-1036). It holds no byte: neither the
 * uploaded sample nor the retained artefact bytes. [checkedBy] is the acting user ID, never a role.
 */
data class SampleMeta(
    val checkId: String,
    val entityRef: String,
    val scenario: String,
    val submissionType: String,
    val sampleSha256: String,
    val result: String,
    val textFound: Boolean,
    val missingWording: List<String>,
    val checkedAt: Instant,
    val checkedBy: String,
    val findingCode: String?,
)

/**
 * The members of a stored payload sample check that the single read uses: those of [SampleMeta] and the uploaded
 * [sampleContent]. It is not a data class, so no generated text or equality touches the bytes.
 */
@Suppress("LongParameterList") // one parameter per member of the payload record, as fixed by INV-T-27776
class SampleRecord(
    val checkId: String,
    val entityRef: String,
    val scenario: String,
    val submissionType: String,
    val sampleContent: ByteArray,
    val sampleSha256: String,
    val result: String,
    val textFound: Boolean,
    val missingWording: List<String>,
    val checkedAt: Instant,
    val checkedBy: String,
    val findingCode: String?,
)

/** The metadata of one sample check with the PAY-014 standing of its entity at read time. */
data class SampleMetaReply(
    val meta: SampleMeta,
    val standingOpen: Boolean,
)

/** The answer of a payload read of one sample check. A refusal carries the owner's error envelope unchanged. */
sealed interface SampleReply<out T> {
    data class Ok<out T>(
        val value: T,
    ) : SampleReply<T>

    data class Forbidden(
        val refusal: OwnerRefusal,
    ) : SampleReply<Nothing>

    data class NotFound(
        val refusal: OwnerRefusal,
    ) : SampleReply<Nothing>

    data class Invalid(
        val refusal: OwnerRefusal,
    ) : SampleReply<Nothing>

    /** No answer, or one that cannot be read. */
    data class Unavailable(
        val reason: String,
    ) : SampleReply<Nothing>
}

/** getRenditionSampleCheckMetadata of payload-svc, used by the list join only. No exception crosses this port. */
fun interface SampleMetaRead {
    fun read(
        entityRef: String,
        checkId: String,
    ): SampleReply<SampleMetaReply>
}

/** getRenditionSampleCheck of payload-svc, used by the single read only. No exception crosses this port. */
fun interface SampleRecordRead {
    fun read(
        entityRef: String,
        checkId: String,
    ): SampleReply<SampleRecord>
}
