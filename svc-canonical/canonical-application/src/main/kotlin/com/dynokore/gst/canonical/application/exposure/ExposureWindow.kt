package com.dynokore.gst.canonical.application.exposure

import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.util.UUID

/**
 * The versions [principalRef] recorded at or after [recordedFrom] and before [recordedTo]. A version matches when its
 * `created_by` equals [principalRef] exactly.
 */
data class ExposureWindow(
    val principalRef: String,
    val recordedFrom: Instant,
    val recordedTo: Instant,
)

/** The place of one version in the listing order: sourceSystem, recordedAt, docUuid, version. */
data class ExposurePosition(
    val sourceSystem: String,
    val recordedAt: Instant,
    val docUuid: UUID,
    val version: Int,
)

/**
 * One version of the listing. [sourceSystem], [sourceKey] and [sourceRevision] are those of the event that the
 * version's stored source link names; [recordedAt] is its `created_at`.
 */
data class ExposedVersion(
    val entityRef: String,
    val sourceSystem: String,
    val sourceKey: String,
    val sourceRevision: Long,
    val docUuid: UUID,
    val version: Int,
    val recordedAt: Instant,
) {
    /** The position a next page continues after. */
    val position: ExposurePosition get() = ExposurePosition(sourceSystem, recordedAt, docUuid, version)
}

/** Read port over the document versions of the bound tenant, in the caller's open `canonical` transaction. */
interface ExposureWindowStore {
    /**
     * The distinct entityRefs of all versions matching [window].
     *
     * @throws IllegalStateException when any matching version has no entityRef stored: the answer is complete or there
     *   is none (NIST SP 800-92).
     */
    fun entityRefs(window: ExposureWindow): Set<String>

    /**
     * At most [limit] versions that match [window] and whose entityRef is in [permittedEntityRefs], in listing order,
     * starting after [after] or at the first when [after] is null. The entityRef and position conditions apply before
     * the limit. An empty [permittedEntityRefs] answers an empty list and runs no statement.
     *
     * @throws IllegalStateException when any version matching [window] has no entityRef stored, whatever
     *   [permittedEntityRefs], [after] and [limit] are, except that an empty set reads nothing.
     * @throws IllegalArgumentException when [limit] is less than 1.
     */
    fun page(
        window: ExposureWindow,
        permittedEntityRefs: Set<String>,
        limit: Int,
        after: ExposurePosition?,
    ): List<ExposedVersion>
}

/** Reads the exposure window of a principal in one transaction of the bound tenant. */
class ReadExposureWindow(
    private val store: ExposureWindowStore,
    private val transaction: TransactionRunner,
) {
    /** @throws IllegalStateException as [ExposureWindowStore.entityRefs]. */
    fun entityRefs(window: ExposureWindow): Set<String> = transaction.inTransaction { store.entityRefs(window) }

    /** As [ExposureWindowStore.page]. */
    fun page(
        window: ExposureWindow,
        permittedEntityRefs: Set<String>,
        limit: Int,
        after: ExposurePosition? = null,
    ): List<ExposedVersion> = transaction.inTransaction { store.page(window, permittedEntityRefs, limit, after) }
}
