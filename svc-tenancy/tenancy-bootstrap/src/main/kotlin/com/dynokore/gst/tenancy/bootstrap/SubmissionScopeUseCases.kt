package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqSubmissionScopeStore
import com.dynokore.gst.tenancy.application.period.GetSubmissionScope
import com.dynokore.gst.tenancy.application.period.PeriodRecordStore
import com.dynokore.gst.tenancy.application.period.SubmissionScopeStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18: getSubmissionScope (INV-REQ-1086, INV-T-27013). The permission is decided per request by `PeriodAccess`. */
@Configuration(proxyBeanMethods = false)
class SubmissionScopeUseCases {
    @Bean
    fun submissionScopeStore(
        dsl: DSLContext,
        tenants: TenantContext,
        periods: PeriodRecordStore,
    ): SubmissionScopeStore = JooqSubmissionScopeStore(dsl, tenants, periods)

    @Bean
    fun getSubmissionScopeUseCase(store: SubmissionScopeStore): GetSubmissionScope = GetSubmissionScope(store)
}
