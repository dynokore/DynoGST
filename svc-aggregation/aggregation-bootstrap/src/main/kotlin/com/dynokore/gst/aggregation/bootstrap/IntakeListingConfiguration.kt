package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.http.IntakeListingCursors
import com.dynokore.gst.aggregation.adapters.http.IntakeListingEndpoint
import com.dynokore.gst.aggregation.adapters.permission.EntityViewPermission
import com.dynokore.gst.aggregation.adapters.persistence.JooqIntakeListingReader
import com.dynokore.gst.aggregation.application.ListSubmittedIntake
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the beans of `listAggregationIntake` (INV-REQ-867). */
@Configuration(proxyBeanMethods = false)
class IntakeListingConfiguration {
    @Bean
    fun listSubmittedIntake(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): ListSubmittedIntake = ListSubmittedIntake(JooqIntakeListingReader(dsl, runner, tenants))

    @Bean
    fun intakeListingCursors(
        secrets: SecretStore,
        clock: Clock,
    ): IntakeListingCursors = IntakeListingCursors(secrets, clock)

    @Bean
    fun intakeListingEndpoint(
        listing: ListSubmittedIntake,
        permission: EntityViewPermission,
        cursors: IntakeListingCursors,
        tenants: TenantContext,
    ): IntakeListingEndpoint = IntakeListingEndpoint(listing, permission, cursors, tenants)
}
