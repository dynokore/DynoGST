package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.http.IngestedDocumentCursors
import com.dynokore.gst.canonical.adapters.persistence.JooqExposureWindowStore
import com.dynokore.gst.canonical.application.exposure.ReadExposureWindow
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The beans of `listIngestedDocuments` (INV-REQ-867); the jOOQ context is the one of the open transaction. */
@Configuration(proxyBeanMethods = false)
class IngestedDocumentsConfiguration {
    @Bean
    fun readExposureWindow(
        dsl: DSLContext,
        tenants: TenantContext,
        transaction: TransactionRunner,
    ): ReadExposureWindow = ReadExposureWindow(JooqExposureWindowStore(dsl, tenants), transaction)

    @Bean
    fun ingestedDocumentCursors(
        secrets: SecretStore,
        clock: Clock,
    ): IngestedDocumentCursors = SignedIngestedDocumentCursors(secrets, clock)
}
