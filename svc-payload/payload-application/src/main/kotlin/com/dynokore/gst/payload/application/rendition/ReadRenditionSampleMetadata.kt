package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.domain.SampleResult

sealed interface ReadRenditionSampleMetadataOutcome {
    data class Found(
        val metadata: StoredSampleMetadata,
        val open: Boolean,
    ) : ReadRenditionSampleMetadataOutcome

    data object Forbidden : ReadRenditionSampleMetadataOutcome

    data object NotFound : ReadRenditionSampleMetadataOutcome
}

/**
 * Reads the metadata of one stored sample check, without sample or artefact bytes (OWASP API4, data minimisation),
 * under the same key and entity scope as [ReadRenditionSampleCheck]. The decision comes first.
 */
class ReadRenditionSampleMetadata(
    private val authority: OverrideAuthority,
    private val reader: RenditionSampleReader,
) {
    fun execute(
        userId: String,
        entityRef: String,
        checkId: String,
    ): ReadRenditionSampleMetadataOutcome =
        if (!authority.holds(userId, RENDITION_EVIDENCE_VIEW, entityRef)) {
            ReadRenditionSampleMetadataOutcome.Forbidden
        } else {
            reader.findMetadata(entityRef, checkId)?.let { metadata ->
                val open = reader.latest(entityRef).values.any { it == SampleResult.FAILED }
                ReadRenditionSampleMetadataOutcome.Found(metadata, open)
            } ?: ReadRenditionSampleMetadataOutcome.NotFound
        }
}
