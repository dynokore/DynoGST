package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The SUB-017 hold of one document version as stored (INV-REQ-1110): one row, never updated. */
data class PriorSolutionHold(
    val tenant: TenantId,
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val reason: HoldReason,
    val code: String,
    val raisedAt: Instant,
)

/**
 * The insert-only store of SUB-017 holds, keyed by (tenant, doc UUID, version). Every call names the tenant. A hold is
 * open exactly when its row has no resolution row, so closing a hold is an insert and no row is ever rewritten.
 */
interface PriorSolutionHoldStore {
    /** Inserts [hold] unless its key exists; returns the stored row, which is the first one on a repeat. */
    fun raise(hold: PriorSolutionHold): PriorSolutionHold

    /** The hold of the version when its row has no resolution row, and null otherwise. */
    fun open(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): PriorSolutionHold?
}
