package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.gst.payload.domain.WordingRequirement
import java.time.Instant

/** The wording the Scenario pack lists for a pair; null for an unknown entity or a pair the pack does not list. */
fun interface RenditionWordingSource {
    fun requirementsFor(
        entityRef: String,
        scenario: String,
        submissionType: SubmissionType,
    ): List<WordingRequirement>?
}

/** Extracts the text of one PDF sample; null when no text can be found. */
fun interface SampleTextExtractor {
    fun extract(pdf: ByteArray): String?
}

/** Sample and standing store (INV-REQ-1033). */
interface RenditionSampleStore {
    fun findRecorded(
        entityRef: String,
        sampleSha256: String,
        scenario: String,
        submissionType: SubmissionType,
    ): SampleCheckResult?

    fun latest(entityRef: String): Map<Pair<String, SubmissionType>, SampleResult>

    fun save(
        entityRef: String,
        check: StoredSampleCheck,
        latest: Map<Pair<String, SubmissionType>, SampleResult>,
    )
}

/**
 * The record kept per sample check (INV-REQ-1033, INV-REQ-1036): the sample bytes as uploaded and the exact bytes
 * hashed into the evidence artefact. `checkedBy` is a user ID, never a role (INV-Q-10647).
 */
@Suppress("ArrayInDataClass")
data class StoredSampleCheck(
    val checkId: String,
    val entityRef: String,
    val scenario: String,
    val submissionType: SubmissionType,
    val sampleContent: ByteArray,
    val sampleSha256: String,
    val result: SampleResult,
    val missingWording: List<String>,
    val textFound: Boolean,
    val checkedAt: Instant,
    val checkedBy: String,
    val artefactBytes: ByteArray,
)

/** A stored sample check without its binary content: a projection of the same record. */
data class StoredSampleMetadata(
    val checkId: String,
    val entityRef: String,
    val scenario: String,
    val submissionType: SubmissionType,
    val sampleSha256: String,
    val result: SampleResult,
    val missingWording: List<String>,
    val textFound: Boolean,
    val checkedAt: Instant,
    val checkedBy: String,
)

/** Reads of the stored sample checks; `find` and `findMetadata` return nothing for a check of another entity. */
interface RenditionSampleReader {
    fun find(
        entityRef: String,
        checkId: String,
    ): StoredSampleCheck?

    fun findMetadata(
        entityRef: String,
        checkId: String,
    ): StoredSampleMetadata?

    fun latest(entityRef: String): Map<Pair<String, SubmissionType>, SampleResult>
}

/** The one transaction boundary of a recording (INV-ADR-283 transactional outbox). */
interface RenditionUnitOfWork {
    fun <T> run(block: () -> T): T
}
