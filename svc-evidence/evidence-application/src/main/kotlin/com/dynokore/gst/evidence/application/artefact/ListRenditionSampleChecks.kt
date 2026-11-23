package com.dynokore.gst.evidence.application.artefact

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The permission key of `listRenditionSampleChecks`; a decision names a permission, never a role (INV-Q-10027). */
const val RENDITION_EVIDENCE_VIEW = "rendition-evidence.view"

/** Issues and opens the opaque, integrity-protected cursor of this read (the adapter signs it). */
interface SampleCheckCursors {
    /** The token for the page after [last]; a missing or short signing key is a failure, never an unsigned token. */
    fun issue(
        entityRef: String,
        userId: String,
        last: SampleCheckPosition,
        now: Instant,
    ): String

    /** The position [token] names for this entity and user, or null for any refusal (same answer for every cause). */
    fun open(
        token: String,
        entityRef: String,
        userId: String,
        now: Instant,
    ): SampleCheckPosition?
}

sealed interface ListRenditionSampleChecksResult {
    /** [nextCursor] is null on the last page. */
    data class Page(
        val items: List<SampleCheckRow>,
        val nextCursor: String?,
    ) : ListRenditionSampleChecksResult

    /** The key is not held on the entity, or the decision is unavailable or failed (fail closed). */
    data object Forbidden : ListRenditionSampleChecksResult

    /** A limit outside 1 to [ListRenditionSampleChecks.MAX_LIMIT] or a cursor that is not valid for this caller. */
    data object Invalid : ListRenditionSampleChecksResult
}

/**
 * The paged SAMPLE_CHECK artefacts of an entity (INV-REQ-1036, INV-AC-27602). Order: decide the key on the entity
 * (fail closed), validate the limit and the cursor, then read. Nothing is written.
 */
class ListRenditionSampleChecks(
    private val replica: AuthorityReplica,
    private val reader: SampleCheckReader,
    private val cursors: SampleCheckCursors,
    private val clock: Clock,
    private val decisionBudget: Duration = DECISION_BUDGET,
) {
    fun list(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ListRenditionSampleChecksResult {
        if (!allowed(tenant, principal, entityRef)) return ListRenditionSampleChecksResult.Forbidden
        val now = clock.instant()
        val after = cursor?.let { cursors.open(it, entityRef, principal.id, now) }
        val valid = limit in 1..MAX_LIMIT && (cursor == null || after != null)
        return if (valid) {
            page(tenant, principal, entityRef, limit, after, now)
        } else {
            ListRenditionSampleChecksResult.Invalid
        }
    }

    @Suppress("LongParameterList") // one read of one page
    private fun page(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        limit: Int,
        after: SampleCheckPosition?,
        now: Instant,
    ): ListRenditionSampleChecksResult {
        val page = reader.page(tenant, entityRef, limit, after)
        return ListRenditionSampleChecksResult.Page(
            page.rows,
            page.last?.let { cursors.issue(entityRef, principal.id, it, now) },
        )
    }

    /** True only for an allowed decision from the replica; a thrown exception or a DEGRADED denial is not allowed. */
    private fun allowed(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
    ): Boolean =
        runCatching {
            replica
                .decide(
                    AuthorizationRequest(tenant, principal, entityRef, RENDITION_EVIDENCE_VIEW),
                    Deadline.after(decisionBudget, clock),
                ).allowed
        }.getOrDefault(false)

    companion object {
        /** The contract's `limit` maximum (`listRenditionSampleChecks`). */
        const val MAX_LIMIT = 500
        private val DECISION_BUDGET: Duration = Duration.ofSeconds(2)
    }
}
