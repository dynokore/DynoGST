package com.dynokore.gst.canonical.adapters.permission

import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/** The one call made on the authority replica: `AuthorityReplica.decide`. */
fun interface ReplicaDecision {
    fun decide(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision
}

/**
 * [EntityViewPermission] over the authority replica, one decision per entity for the person (kind HUMAN), the bound
 * tenant, the entity and the exact key [PERMISSION]. A decision counts only with source LOCAL. With no replica bound,
 * when the call throws, with any other source or with the reason AUTHORITY_UNAVAILABLE the answer is null (fail
 * closed, NIST SP 800-53 AC-3); only a LOCAL refusal filters an entity.
 */
class ReplicaEntityViewPermission(
    private val replica: ReplicaDecision?,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val budget: Duration,
) : EntityViewPermission {
    override fun permittedAmong(
        actor: String,
        entityRefs: Set<String>,
    ): Set<String>? {
        val decisions = mutableMapOf<String, Boolean>()
        // stops at the first unavailable decision: the request is refused whatever the others answer
        val complete =
            entityRefs.all { entityRef ->
                val decision = decided(actor, entityRef)
                if (decision != null) decisions[entityRef] = decision.allowed
                decision != null
            }
        return if (complete) decisions.filterValues { it }.keys else null
    }

    @Suppress("TooGenericExceptionCaught") // fail closed: any failure leaves the decision unavailable
    private fun decided(
        actor: String,
        entityRef: String,
    ): AuthorityDecision? {
        val bound = replica ?: return null
        val decision =
            try {
                bound.decide(
                    AuthorizationRequest(
                        tenants.current(),
                        Principal(actor, PrincipalKind.HUMAN),
                        entityRef,
                        PERMISSION,
                    ),
                    Deadline.after(budget, clock),
                )
            } catch (e: Exception) {
                LOG.warn("the {} decision failed", PERMISSION, e)
                null
            }
        return decision?.takeIf {
            it.source == DecisionSource.LOCAL && it.reason != DecisionReason.AUTHORITY_UNAVAILABLE
        }
    }

    companion object {
        const val PERMISSION = "submission.view-status"
        private val LOG: Logger = LoggerFactory.getLogger(ReplicaEntityViewPermission::class.java)
    }
}
