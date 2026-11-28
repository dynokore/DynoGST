package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ApportionmentModeProfileExtras
import com.dynokore.gst.tenancy.adapters.election.PurchaseDateBasisProfileExtras
import com.dynokore.gst.tenancy.adapters.events.EntityProfileReplaySource
import com.dynokore.gst.tenancy.adapters.events.OrganisationStructureReplaySource
import com.dynokore.gst.tenancy.adapters.persistence.JooqReplayReader
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * The producer-side replay sources of tenancy-svc (INV-T-9096, INV-Q-9081 A): one [ReplayStateSource] per event type
 * it emits as current state. The responder, the topics and the driver are built by the catalogue of INV-T-25011 and by
 * [ReplayResponderConfiguration] (INV-T-25012).
 */
@Configuration(proxyBeanMethods = false)
class ReplaySourcesConfiguration {
    @Bean
    fun replayReader(
        dsl: DSLContext,
        tenants: TenantContext,
        elections: ElectionPolicyStore,
    ): ReplayReader = JooqReplayReader(dsl, tenants, replayProfileEntries(elections))

    @Bean
    fun organisationStructureReplaySource(
        reader: ReplayReader,
        mapper: ObjectMapper,
    ): ReplayStateSource = OrganisationStructureReplaySource(reader, mapper)

    @Bean
    fun entityProfileReplaySource(
        reader: ReplayReader,
        mapper: ObjectMapper,
    ): ReplayStateSource = EntityProfileReplaySource(reader, mapper)
}

/** The election entries the replay appends to an entity profile: purchase date basis, then apportionment mode. */
fun replayProfileEntries(elections: ElectionPolicyStore): (String) -> List<ProfileEntry> {
    val purchaseDateBasis = PurchaseDateBasisProfileExtras(elections)
    val apportionmentMode = ApportionmentModeProfileExtras(elections)
    return { entityRef -> purchaseDateBasis.entries(entityRef) + apportionmentMode.entries(entityRef) }
}
