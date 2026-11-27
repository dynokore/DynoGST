package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.permission.EntityViewPermission
import com.dynokore.gst.aggregation.adapters.permission.ReplicaDecision
import com.dynokore.gst.aggregation.adapters.permission.ReplicaEntityViewPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/**
 * The one authorization wiring of the service (ARC-03, OWASP ASVS V4): the permission port over the authority replica
 * (INV-Q-10027). The replica and the operation-role map are created by the platform configurations that
 * [AggregationApplication] imports. With no replica bound the permission refuses (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class PermissionConfiguration {
    /** The decide budget comes from [BUDGET_PROPERTY] (12-factor III), default [DEFAULT_BUDGET]. */
    @Bean
    @ConditionalOnMissingBean(EntityViewPermission::class)
    fun entityViewPermission(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
        environment: Environment,
    ): EntityViewPermission =
        ReplicaEntityViewPermission(
            replica.getIfAvailable()?.let { bound -> ReplicaDecision(bound::decide) },
            tenants,
            clock.getIfAvailable { Clock.systemUTC() },
            Duration.parse(environment.getProperty(BUDGET_PROPERTY, DEFAULT_BUDGET)),
        )

    companion object {
        const val BUDGET_PROPERTY = "aggregation.permission.decide-budget"
        const val DEFAULT_BUDGET = "PT1S"
    }
}
