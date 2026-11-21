package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.application.alerting.RoleHolders
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.stereotype.Component

/**
 * The holders of a role read from the authority replica (INV-REQ-910): the TENANT-level holders of the tenant only,
 * so no holder of another tenant is returned (tenant isolation, OWASP ASVS V4, NIST SP 800-53 AC-3).
 */
@Component
class AuthorityReplicaRoleHolders(
    private val replica: AuthorityReplica,
) : RoleHolders {
    override fun holders(
        tenant: TenantId,
        role: String,
    ): List<String> = replica.holdersAt(tenant, role, ScopeLevel.TENANT).toList()
}
