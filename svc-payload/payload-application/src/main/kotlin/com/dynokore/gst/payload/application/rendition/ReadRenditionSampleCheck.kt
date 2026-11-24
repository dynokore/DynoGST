package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.domain.SampleResult

/** The scoped permission key that reads a stored sample check at entity scope (INV-REQ-1036). */
const val RENDITION_EVIDENCE_VIEW = "rendition-evidence.view"

sealed interface ReadRenditionSampleCheckOutcome {
    /** `open` is derived from the stored latest results on each read, never stored. */
    data class Found(
        val check: StoredSampleCheck,
        val open: Boolean,
    ) : ReadRenditionSampleCheckOutcome

    data object Forbidden : ReadRenditionSampleCheckOutcome

    data object NotFound : ReadRenditionSampleCheckOutcome
}

/**
 * Reads one stored sample check for a holder of `rendition-evidence.view` at entity scope. The decision comes
 * first, so a non-holder learns nothing about the existence of a check (OWASP ASVS V4).
 */
class ReadRenditionSampleCheck(
    private val authority: OverrideAuthority,
    private val reader: RenditionSampleReader,
) {
    fun execute(
        userId: String,
        entityRef: String,
        checkId: String,
    ): ReadRenditionSampleCheckOutcome =
        if (!authority.holds(userId, RENDITION_EVIDENCE_VIEW, entityRef)) {
            ReadRenditionSampleCheckOutcome.Forbidden
        } else {
            reader.find(entityRef, checkId)?.let { check ->
                val open = reader.latest(entityRef).values.any { it == SampleResult.FAILED }
                ReadRenditionSampleCheckOutcome.Found(check, open)
            } ?: ReadRenditionSampleCheckOutcome.NotFound
        }
}
