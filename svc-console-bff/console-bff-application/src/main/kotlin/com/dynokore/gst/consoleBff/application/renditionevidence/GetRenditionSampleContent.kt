package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal

/** The answer of getRenditionSampleContent. Only [Content] holds bytes. */
sealed interface SampleContentResult {
    /** The verified bytes of one sample check. Not a data class, so no generated text or equality touches them. */
    class Content(
        val checkId: String,
        val sampleSha256: String,
        val sampleContent: ByteArray,
    ) : SampleContentResult

    data class Forbidden(
        val refusal: OwnerRefusal,
    ) : SampleContentResult

    data class NotFound(
        val refusal: OwnerRefusal,
    ) : SampleContentResult

    data class Invalid(
        val refusal: OwnerRefusal,
    ) : SampleContentResult

    data class Unavailable(
        val reason: String,
    ) : SampleContentResult

    data class IntegrityFailure(
        val failure: Verification.Failed,
    ) : SampleContentResult
}

/**
 * INV-REQ-1036: the bytes of one sample check. The payload record is read once; the bytes are returned only when its
 * identity is that of the request and they hash to its sampleSha256 (fail closed). The permission is decided by the
 * read filter, not here.
 */
class GetRenditionSampleContent(
    private val records: SampleRecordRead,
) {
    fun execute(
        entityRef: String,
        checkId: String,
    ): SampleContentResult =
        when (val reply = records.read(entityRef, checkId)) {
            is SampleReply.Ok -> verified(entityRef, checkId, reply.value)
            is SampleReply.Forbidden -> SampleContentResult.Forbidden(reply.refusal)
            is SampleReply.NotFound -> SampleContentResult.NotFound(reply.refusal)
            is SampleReply.Invalid -> SampleContentResult.Invalid(reply.refusal)
            is SampleReply.Unavailable -> SampleContentResult.Unavailable(reply.reason)
        }

    private fun verified(
        entityRef: String,
        checkId: String,
        record: SampleRecord,
    ): SampleContentResult {
        val identity = SampleRecordVerifier.identity(entityRef, checkId, record)
        val outcome = if (identity is Verification.Failed) identity else SampleRecordVerifier.content(record)
        return when (outcome) {
            is Verification.Failed -> {
                SampleContentResult.IntegrityFailure(outcome)
            }

            Verification.Verified -> {
                SampleContentResult.Content(record.checkId, record.sampleSha256, record.sampleContent)
            }
        }
    }
}
