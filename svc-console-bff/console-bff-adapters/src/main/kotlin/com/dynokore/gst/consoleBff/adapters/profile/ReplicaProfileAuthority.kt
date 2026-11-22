package com.dynokore.gst.consoleBff.adapters.profile

import com.dynokore.gst.consoleBff.application.profile.OPERATING_PROFILE_APPLY
import com.dynokore.gst.consoleBff.application.profile.ProfileAuthority
import com.dynokore.gst.consoleBff.application.profile.ProfileScope
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * Answers whether the user may apply an operating profile at the requested scope, decided per request by
 * [AuthorityReplica.decide] on the scoped permission key at the level of the scope, and never cached
 * (INV-Q-10027; OWASP ASVS V4.1.1). Fail closed: only `allowed == true` gives true; a deny, a DEGRADED or null
 * decision and a thrown exception give false.
 */
class ReplicaProfileAuthority(
    private val replica: AuthorityReplica,
    private val clock: Clock,
    private val budget: Duration,
) : ProfileAuthority {
    override fun holds(
        tenantId: String,
        userId: String,
        entityRef: String,
        scope: ProfileScope,
    ): Boolean =
        runCatching {
            replica.decide(
                AuthorizationRequest(
                    TenantId.of(tenantId),
                    Principal(userId, PrincipalKind.HUMAN),
                    entityRef,
                    OPERATING_PROFILE_APPLY,
                    level =
                        when (scope) {
                            ProfileScope.TENANT -> ScopeLevel.TENANT
                            ProfileScope.ENTITY -> ScopeLevel.ENTITY
                        },
                ),
                Deadline.after(budget, clock),
            )
        }.getOrNull()?.allowed == true
}
