package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** The scoped permission key of `getWorkPaper` (platform-contracts `catalogue/role-permissions`, kind READ). */
const val WORK_PAPER_VIEW_PERMISSION = "work-paper.view"

/** Decides whether [userId] may view the work-paper of [entityRef] now, for the bound tenant. */
fun interface WorkPaperViewAuthority {
    fun allows(
        userId: String,
        entityRef: String,
    ): Boolean
}

/**
 * [WorkPaperViewAuthority] over `AuthorityReplica.decide` (INV-Q-10027, INV-T-28549). One decision of
 * [WORK_PAPER_VIEW_PERMISSION] is asked per request; no role name is read. Only a LOCAL or FALLBACK decision that
 * allows grants: a deny, a DEGRADED (stale or unavailable) answer and an exception all refuse (deny by default and
 * fail closed, ASVS V4.1, NIST SP 800-53 AC-3). The log of a failed decision names the exception type only.
 */
class AuthorityReplicaWorkPaperViewAuthority internal constructor(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
) : WorkPaperViewAuthority {
    constructor(replica: AuthorityReplica, tenants: TenantContext) : this(replica::decide, tenants)

    @Suppress("TooGenericExceptionCaught") // every failure of the decision is a refusal (fail closed)
    override fun allows(
        userId: String,
        entityRef: String,
    ): Boolean =
        try {
            val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
            val decision =
                decide(
                    AuthorizationRequest(
                        tenants.current(),
                        Principal(userId, PrincipalKind.HUMAN),
                        entityRef,
                        WORK_PAPER_VIEW_PERMISSION,
                    ),
                    deadline,
                )
            decision.allowed && decision.source in GRANTING
        } catch (e: Exception) {
            LOG.warn("the decision of {} failed and is refused: {}", WORK_PAPER_VIEW_PERMISSION, e.javaClass.name)
            false
        }

    private companion object {
        val GRANTING = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
        val LOG: Logger = LoggerFactory.getLogger(AuthorityReplicaWorkPaperViewAuthority::class.java)
    }
}
