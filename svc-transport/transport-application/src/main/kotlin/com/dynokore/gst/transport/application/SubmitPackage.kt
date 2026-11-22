package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome

/** A package breaks the rules of INV-REQ-424: member count, distinct Doc UUIDs, 10 MB total, declared size. */
class InvalidPackage(
    reason: String,
) : IllegalArgumentException("invalid package: $reason")

/** One document of a package, its payload by value; [sizeBytes] is the size the caller declares for it. */
data class PackageMember(
    val doc: DocRef,
    val payload: ByteArray,
    val sizeBytes: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is PackageMember &&
            doc == other.doc &&
            sizeBytes == other.sizeBytes &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * doc.hashCode() + sizeBytes.hashCode()) + payload.contentHashCode()
}

/** The package send port: one call hands the whole package to the adapter, which answers one outcome for it all. */
interface PackageSender {
    fun send(
        adapter: AdapterId,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): TransportOutcome
}

/** The answer of [SubmitPackage]: one outcome that applies to every member (INV-REQ-553), one entry per document. */
data class PackageOutcome(
    val outcome: TransportOutcome,
    val members: List<DocRef>,
)

/**
 * The submit-package operation of the internal transport interface (INV-REQ-424, INV-REQ-553): 1 to 10 members, no
 * two with one Doc UUID, the declared sizes summing to at most 10000000 bytes (nothing is truncated) and each real
 * payload length equal to its declared size. A Doc UUID recorded under another entity is refused (INV-Q-8172) and so
 * is an entity with no adapter assigned. Nothing is sent on any refusal; otherwise the whole package is sent once
 * through the adapter of the entity's connection. No role is checked and no authorisation is asked here.
 */
class SubmitPackage(
    private val attempts: AttemptLog,
    private val assignments: EntityAdapterAssignments,
    private val sender: PackageSender,
) {
    fun submit(
        entity: EntityRef,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): PackageOutcome {
        validate(documentTypeCode, members)
        members.forEach { m ->
            val recorded = attempts.entityOf(m.doc.docUuid)
            if (recorded != null && recorded != entity) throw DocUuidRecordedUnderOtherEntity(m.doc.docUuid)
        }
        val adapter = assignments.adapterFor(entity) ?: throw NoAdapterConfigured(entity)
        val outcome = sender.send(adapter, documentTypeCode, members)
        return PackageOutcome(outcome, members.map { it.doc })
    }

    private fun validate(
        documentTypeCode: String,
        members: List<PackageMember>,
    ) {
        val reason = violation(documentTypeCode, members)
        if (reason != null) throw InvalidPackage(reason)
    }

    @Suppress("ReturnCount")
    private fun violation(
        documentTypeCode: String,
        members: List<PackageMember>,
    ): String? {
        if (documentTypeCode.isBlank()) return "the document type code is blank"
        if (members.size !in 1..MAX_MEMBERS) return "1 to $MAX_MEMBERS members, got ${members.size}"
        if (members.map { it.doc.docUuid }.toSet().size != members.size) return "a Doc UUID repeats"
        if (members.any { it.sizeBytes < 0 }) return "a size is negative"
        if (members.sumOf { it.sizeBytes } > MAX_TOTAL_BYTES) return "more than $MAX_TOTAL_BYTES bytes"
        if (members.any { it.payload.size.toLong() != it.sizeBytes }) return "a size differs from its payload"
        return null
    }

    private companion object {
        const val MAX_MEMBERS = 10
        const val MAX_TOTAL_BYTES = 10_000_000L
    }
}
