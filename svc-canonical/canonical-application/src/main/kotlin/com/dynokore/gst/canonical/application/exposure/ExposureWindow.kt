package com.dynokore.gst.canonical.application.exposure

import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant

/**
 * The versions [principalRef] recorded at or after [recordedFrom] and before [recordedTo]. A version matches when its
 * `created_by` equals [principalRef] exactly.
 */
data class ExposureWindow(
    val principalRef: String,
    val recordedFrom: Instant,
    val recordedTo: Instant,
)

/** Read port over the document versions of the bound tenant, in the caller's open `canonical` transaction. */
interface ExposureWindowStore {
    /**
     * The distinct entityRefs of all versions matching [window].
     *
     * @throws IllegalStateException when any matching version has no entityRef stored: the answer is complete or there
     *   is none (NIST SP 800-92).
     */
    fun entityRefs(window: ExposureWindow): Set<String>
}

/** Reads the exposure window of a principal in one transaction of the bound tenant. */
class ReadExposureWindow(
    private val store: ExposureWindowStore,
    private val transaction: TransactionRunner,
) {
    /** @throws IllegalStateException as [ExposureWindowStore.entityRefs]. */
    fun entityRefs(window: ExposureWindow): Set<String> = transaction.inTransaction { store.entityRefs(window) }
}
