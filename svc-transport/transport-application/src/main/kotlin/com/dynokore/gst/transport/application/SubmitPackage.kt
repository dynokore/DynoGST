package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/** A package breaks the rules of INV-REQ-424: member count, distinct Doc UUIDs, 10 MB total, declared size. */
class InvalidPackage(
    reason: String,
) : IllegalArgumentException("invalid package: $reason")

/** The packageRef is already recorded for a package with other content: nothing is sent. */
class PackageRefReused(
    packageRef: String,
) : IllegalStateException("packageRef $packageRef was already used for another package")

/** The package of this packageRef was claimed and has no outcome yet: nothing is sent a second time. */
class PackageInFlight(
    packageRef: String,
) : IllegalStateException("the package of packageRef $packageRef is still in flight")

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
    /** Whether [adapter] is registered here and able to send a package. */
    fun canSend(adapter: AdapterId): Boolean

    fun send(
        adapter: AdapterId,
        packageRef: String,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): TransportOutcome
}

/** What [PackageLog.begin] records before the send: the package and the Doc refs of its members. */
data class PackageSend(
    val packageRef: String,
    val entity: EntityRef,
    val fingerprint: String,
    val adapter: AdapterId,
    val members: List<DocRef>,
)

/** A stored package: its content fingerprint, when it was claimed and its outcome, null while it has none. */
data class PackageRecord(
    val fingerprint: String,
    val startedAt: Instant,
    val outcome: TransportOutcome?,
)

/** The package records, one per packageRef, with the attempts of their members (INV-REQ-538). */
interface PackageLog {
    fun find(packageRef: String): PackageRecord?

    /**
     * In one committed transaction records the package and, per member, one attempt with one egress, and returns
     * null. When the packageRef is already stored, writes nothing and returns that record.
     */
    fun begin(
        send: PackageSend,
        at: Instant,
    ): PackageRecord?

    /** Sets [outcome] on the package and its member attempts only where none is set; returns the one then stored. */
    fun complete(
        packageRef: String,
        outcome: TransportOutcome,
        at: Instant,
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
 * payload length equal to its declared size; the packageRef is not blank and at most 40 characters.
 *
 * A packageRef already stored is answered from its record and nothing is sent: other content is refused as
 * [PackageRefReused]; a stored outcome is returned; a claim without one is [PackageInFlight] until it is older than
 * [staleAfter], then it is completed as a transient failure.
 *
 * Otherwise a Doc UUID recorded under another entity is refused (INV-Q-8172), and so are a member with a settled
 * outcome, an entity with no adapter assigned, a member in flight on another adapter and an adapter that cannot send
 * a package. Nothing is sent on any refusal. The package and its member attempts are recorded before the whole
 * package is sent once through the adapter of the entity's connection. No role is checked and no authorisation is
 * asked here.
 */
class SubmitPackage(
    private val attempts: AttemptLog,
    private val assignments: EntityAdapterAssignments,
    private val sender: PackageSender,
    private val packages: PackageLog,
    private val clock: Clock,
    private val staleAfter: Duration,
) {
    fun submit(
        packageRef: String,
        entity: EntityRef,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): PackageOutcome {
        validate(packageRef, documentTypeCode, members)
        val docs = members.map { it.doc }
        val fingerprint = fingerprint(entity, documentTypeCode, members)
        val stored = packages.find(packageRef)
        val outcome =
            if (stored != null) {
                repeated(packageRef, fingerprint, stored)
            } else {
                val adapter = admit(entity, docs)
                val claim = PackageSend(packageRef, entity, fingerprint, adapter, docs)
                val raced = packages.begin(claim, clock.instant())
                if (raced != null) {
                    repeated(packageRef, fingerprint, raced)
                } else {
                    send(adapter, packageRef, documentTypeCode, members)
                }
            }
        return PackageOutcome(outcome, docs)
    }

    private fun repeated(
        packageRef: String,
        fingerprint: String,
        record: PackageRecord,
    ): TransportOutcome {
        if (record.fingerprint != fingerprint) throw PackageRefReused(packageRef)
        record.outcome?.let { return it }
        val now = clock.instant()
        if (Duration.between(record.startedAt, now) <= staleAfter) throw PackageInFlight(packageRef)
        return packages.complete(packageRef, TransportOutcome.TransientFailure, now)
    }

    /** The checks a package passes before it is claimed; returns the adapter to send it through. */
    private fun admit(
        entity: EntityRef,
        docs: List<DocRef>,
    ): AdapterId {
        requireOpenMembers(entity, docs)
        val adapter = assignments.adapterFor(entity) ?: throw NoAdapterConfigured(entity)
        requireNoneInFlightElsewhere(adapter, docs)
        if (!sender.canSend(adapter)) throw AdapterNotRegistered(adapter.value)
        return adapter
    }

    private fun requireOpenMembers(
        entity: EntityRef,
        docs: List<DocRef>,
    ) {
        docs.forEach { doc ->
            val recorded = attempts.entityOf(doc.docUuid)
            if (recorded != null && recorded != entity) throw DocUuidRecordedUnderOtherEntity(doc.docUuid)
        }
        if (docs.any { attempts.settledOutcome(it) != null }) throw InvalidPackage("a member is already settled")
    }

    private fun requireNoneInFlightElsewhere(
        adapter: AdapterId,
        docs: List<DocRef>,
    ) {
        if (docs.any { (attempts.adapterInFlight(it) ?: adapter) != adapter }) {
            throw InvalidPackage("a member is in flight on another adapter")
        }
    }

    private fun send(
        adapter: AdapterId,
        packageRef: String,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): TransportOutcome {
        // an adapter that fails outside its own classification is still only a transient failure upstream
        val outcome =
            runCatching { sender.send(adapter, packageRef, documentTypeCode, members) }
                .getOrElse { TransportOutcome.TransientFailure }
        return packages.complete(packageRef, outcome, clock.instant())
    }

    /** SHA-256 over the length-prefixed content of the package, members in request order; not the packageRef. */
    private fun fingerprint(
        entity: EntityRef,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): String {
        val fields =
            listOf(entity.value.toByteArray(), documentTypeCode.toByteArray()) +
                members.flatMap {
                    listOf(
                        it.doc.docUuid
                            .toString()
                            .toByteArray(),
                        it.doc.version
                            .toString()
                            .toByteArray(),
                        sha256(it.payload),
                    )
                }
        val encoded = ByteArrayOutputStream()
        DataOutputStream(encoded).use { out ->
            fields.forEach {
                out.writeInt(it.size)
                out.write(it)
            }
        }
        return HexFormat.of().formatHex(sha256(encoded.toByteArray()))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun validate(
        packageRef: String,
        documentTypeCode: String,
        members: List<PackageMember>,
    ) {
        val reason =
            if (packageRef.isBlank() || packageRef.length > MAX_REF_LENGTH) {
                "the packageRef is blank or longer than $MAX_REF_LENGTH characters"
            } else {
                violation(documentTypeCode, members)
            }
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
        const val MAX_REF_LENGTH = 40
    }
}
