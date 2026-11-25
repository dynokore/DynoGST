package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PriorSolutionHold
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.VersionState
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** [InFlightStore] over the generated jOOQ classes of `submission` (CS-18), in the open tenant transaction. */
class JooqInFlightStore(
    private val dsl: DSLContext,
) : InFlightStore {
    override fun lockVersions(
        tenant: TenantId,
        docUuid: String,
    ): List<VersionState> {
        val s = Submission.SUBMISSION_
        return dsl
            .select(s.VERSION, s.STATE, s.SUBMISSION_TYPE, DSL.field(priorSolutionHoldExists()))
            .from(s)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.DOC_UUID.eq(docUuid))
            .orderBy(s.VERSION)
            .forUpdate()
            .fetch()
            .map {
                VersionState(
                    checkNotNull(it.value1()),
                    LifecycleState.valueOf(checkNotNull(it.value2())),
                    SubmissionKind.valueOf(checkNotNull(it.value3())),
                    checkNotNull(it.value4()),
                )
            }
    }

    override fun waitingDocUuids(
        tenant: TenantId,
        limit: Int,
    ): List<String> {
        val s = Submission.SUBMISSION_
        return dsl
            .selectDistinct(s.DOC_UUID)
            .from(s)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.STATE.eq(LifecycleState.READY.name))
            .and(s.SUBMISSION_TYPE.ne(SubmissionKind.TYPE_1A.name))
            .andNot(priorSolutionHoldExists())
            .and(
                s.DOC_UUID.notIn(
                    dsl
                        .select(s.DOC_UUID)
                        .from(s)
                        .where(s.TENANT_ID.eq(tenant.value))
                        .and(s.STATE.`in`(InFlight.NAMES)),
                ),
            ).orderBy(s.DOC_UUID)
            .limit(limit)
            .fetch(s.DOC_UUID)
            .map { checkNotNull(it) }
    }
}

/**
 * True when the `submission` row of the enclosing statement has a SUB-017 prior-solution hold row (INV-REQ-1110). The
 * resolution table is not read: a resolved hold excludes the version as an open one does. The one hold predicate of the
 * enqueue funnel, the waiting read and the retry due-scan. An EXISTS subquery: a `FOR UPDATE` of the enclosing select
 * locks no hold row (the runtime role holds no update grant on the hold table).
 */
internal fun priorSolutionHoldExists(): Condition {
    val s = Submission.SUBMISSION_
    val h = PriorSolutionHold.PRIOR_SOLUTION_HOLD
    return DSL.exists(
        DSL
            .selectOne()
            .from(h)
            .where(h.TENANT_ID.eq(s.TENANT_ID))
            .and(h.DOC_UUID.eq(s.DOC_UUID))
            .and(h.VERSION.eq(s.VERSION)),
    )
}
