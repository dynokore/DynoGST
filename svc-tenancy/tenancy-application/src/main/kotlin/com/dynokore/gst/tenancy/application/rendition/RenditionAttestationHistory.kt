package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import java.time.Instant

/** Why a history row was written. */
enum class RenditionRecordKind {
    /** An attestation was recorded (the first or a replacement). */
    ATTESTED,

    /** The state of the current attestation changed (for example to REQUESTED_AGAIN). */
    STATE_CHANGED,
}

/** One row of the append-only attestation history (INV-REQ-1036 behavior 1). [attestedBy] is a user ID. */
data class RenditionAttestationRecord(
    val seq: Long,
    val kind: RenditionRecordKind,
    val renderer: Renderer,
    val attestedBy: String,
    val attestedAt: Instant,
    val state: AttestationState,
)

/** The position of a history row in the (attestedAt, seq) order; a page starts strictly after it. */
data class RenditionHistoryPosition(
    val attestedAt: Instant,
    val seq: Long,
)

/** One row of the history of an entity across document classes (INV-REQ-1036). [attestedBy] is a user ID. */
data class RenditionHistoryEntry(
    val documentClass: String,
    val seq: Long,
    val renderer: Renderer,
    val attestedBy: String,
    val attestedAt: Instant,
    val state: AttestationState,
)

/** At most `limit` rows; [last] is the position of the last row when more rows exist, else null. */
data class RenditionHistoryPage(
    val entries: List<RenditionHistoryEntry>,
    val last: RenditionHistoryPosition?,
)

/** The append-only attestation history of one entity and class of the bound tenant (adapter: JooqRenditionStore). */
interface RenditionAttestationHistory {
    /** Every record of the entity and class, ordered by `seq`; empty when none. */
    fun list(
        entityRef: String,
        documentClass: String,
    ): List<RenditionAttestationRecord>

    /**
     * Up to [limit] rows of the entity across document classes, ordered by (attestedAt, seq) ascending, strictly after
     * [after] when given; the bound tenant and the entity only.
     */
    fun page(
        entityRef: String,
        limit: Int,
        after: RenditionHistoryPosition?,
    ): RenditionHistoryPage
}
