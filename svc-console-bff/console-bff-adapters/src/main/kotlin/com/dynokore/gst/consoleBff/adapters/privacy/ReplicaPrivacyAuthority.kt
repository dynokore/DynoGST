package com.dynokore.gst.consoleBff.adapters.privacy

import com.dynokore.gst.consoleBff.application.privacy.PRIVACY_REQUEST_HANDLE
import com.dynokore.gst.consoleBff.application.privacy.PrivacyAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * Answers whether the user holds `privacy-request.handle` on the entity, decided per request by
 * [AuthorityReplica.decide] and never cached (INV-Q-10027; OWASP ASVS V4.1.1). Fail closed: only a decision with
 * `allowed == true` gives true; a denied or DEGRADED decision, a null decision and a thrown exception give false.
 */
class ReplicaPrivacyAuthority(
    private val replica: AuthorityReplica,
    private val clock: Clock,
    private val budget: Duration,
) : PrivacyAuthority {
    override fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
    ): Boolean =
        runCatching {
            replica.decide(
                AuthorizationRequest(
                    TenantId.of(tenantId),
                    Principal(principalId, PrincipalKind.HUMAN),
                    entityRef,
                    PRIVACY_REQUEST_HANDLE,
                ),
                Deadline.after(budget, clock),
            )
        }.getOrNull()?.allowed == true
}
