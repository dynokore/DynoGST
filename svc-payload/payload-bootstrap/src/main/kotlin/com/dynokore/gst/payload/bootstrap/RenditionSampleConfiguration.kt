package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.persistence.JooqRenditionSampleStore
import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleCheck
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleMetadata
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The stored rendition sample checks (INV-REQ-1033, INV-REQ-1036) composed: one store bean that is both ports, and the
 * two read use cases over the `overrideAuthority` bean. No `RecordRenditionSample` bean: its other ports have no
 * adapter yet.
 */
@Configuration(proxyBeanMethods = false)
class RenditionSampleConfiguration {
    @Bean
    fun renditionSampleStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqRenditionSampleStore = JooqRenditionSampleStore(dsl) { tenants.current().value }

    @Bean
    fun readRenditionSampleCheck(
        overrideAuthority: OverrideAuthority,
        store: JooqRenditionSampleStore,
    ): ReadRenditionSampleCheck = ReadRenditionSampleCheck(overrideAuthority, store)

    @Bean
    fun readRenditionSampleMetadata(
        overrideAuthority: OverrideAuthority,
        store: JooqRenditionSampleStore,
    ): ReadRenditionSampleMetadata = ReadRenditionSampleMetadata(overrideAuthority, store)
}
