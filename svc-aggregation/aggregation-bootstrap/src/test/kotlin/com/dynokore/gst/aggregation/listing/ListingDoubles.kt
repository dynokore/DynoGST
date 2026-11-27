package com.dynokore.gst.aggregation.listing

import com.dynokore.gst.aggregation.adapters.permission.EntityViewPermission
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.FlowContextKeys
import com.dynokore.platform.serviceruntime.identity.FlowContextTestSupport
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.UUID

/**
 * The doubles of the HTTP cases of `listAggregationIntake`: the workload and end-user verifiers and the roles the
 * permission matrix would list. They sit outside the packages the application scans, so only a test that names them
 * has them. The replica, the controller, the filters and the database are the service's own.
 */
@Configuration(proxyBeanMethods = false)
class ListingDoubles {
    @Bean
    @Primary
    fun listingWorkloadVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value == "token-$CALLER") {
                WorkloadVerification.Verified("sa-$CALLER")
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }

    /** `Bearer user-a` and `Bearer user-b` are two end users of one tenant. */
    @Bean
    @Primary
    fun listingEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            authorization
                ?.removePrefix("Bearer ")
                ?.takeIf { it == USER_A || it == USER_B }
                ?.let { EndUserPrincipal(it, TenantId.of(TENANT.toString()), emptySet()) }
        }

    @Bean
    fun listingFlowContextKeys(): FlowContextKeys = FlowContextTestSupport.keys()

    /** Role names are test data: the service asks only for a permission key. */
    @Bean
    @Primary
    fun listingRoles(): OperationRoleMap =
        OperationRoleMap { key ->
            when (key) {
                VIEW -> mapOf(VIEWER to CellAllowance.PERMITTED)
                LOOK_ALIKE -> mapOf(LOOK_ALIKE_HOLDER to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }

    companion object {
        const val CALLER = "console-bff"
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val VIEW = "submission.view-status"
        const val LOOK_ALIKE = "submission.view-statuses"
        const val VIEWER = "fixture-viewer"
        const val LOOK_ALIKE_HOLDER = "fixture-look-alike-holder"
        val TENANT: UUID = UUID.fromString("7d1c4b52-90a3-4c8e-8f61-2b9e5a0d3c44")
    }
}

/**
 * Replaces the permission port of the composed service by the one [source] builds from the running context, so a test
 * can put a failing decision, or no replica, behind the service's own [EntityViewPermission] implementation.
 */
@Configuration(proxyBeanMethods = false)
class ScriptedPermission {
    @Bean
    @Primary
    fun scriptedEntityViewPermission(context: ApplicationContext): EntityViewPermission =
        EntityViewPermission { actor, refs -> checkNotNull(source)(context).permittedAmong(actor, refs) }

    companion object {
        /** Set by the test that includes this configuration; the tests of one class run one at a time. */
        var source: ((ApplicationContext) -> EntityViewPermission)? = null
    }
}
