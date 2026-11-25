package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.application.preissuance.PreIssuanceAuthority
import com.dynokore.gst.payload.application.preissuance.PreIssuanceCaller
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.Duration

/**
 * INV-REQ-957, INV-Q-10027: [PreIssuanceAuthority] over `AuthorityReplica.decide`. The verified `Principal` of the
 * caller is decided as it is (its own kind: person key for HUMAN, machine key with entity coverage for MACHINE); the
 * permission key is the one the use case passes. It is held only when the decision allows it and its source is LOCAL or
 * FALLBACK; a DEGRADED source and a denial answer false (fail closed). No role name is read here.
 */
class ReplicaPreIssuanceAuthority(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
    private val clock: Clock,
) : PreIssuanceAuthority {
    constructor(replica: AuthorityReplica, tenants: TenantContext, clock: Clock) :
        this(replica::decide, tenants, clock)

    override fun holds(
        caller: PreIssuanceCaller,
        permission: String,
        entityRef: String,
    ): Boolean {
        val request =
            AuthorizationRequest(
                tenant = tenants.current(),
                principal = caller.principal,
                entityRef = entityRef,
                action = permission,
            )
        val deadline = CurrentDeadline.current() ?: Deadline.after(FALLBACK_TIMEOUT, clock)
        val decision = decide(request, deadline)
        return decision.allowed && decision.source in TRUSTED_SOURCES
    }

    private companion object {
        val TRUSTED_SOURCES = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
        val FALLBACK_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}
