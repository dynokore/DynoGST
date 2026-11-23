package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.portfolio.JooqPortfolioStore
import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.gst.consoleBff.application.portfolio.GetPortfolio
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioStore
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * The getPortfolio read (INV-REQ-904): the use case over the jOOQ read model and the replica scope, and its
 * controller.
 * The route is refused without `period-readiness.view` by the read filter; the entity scope is decided again here per
 * request (INV-Q-10027). No downstream call is wired: the model is filled by events, not by this read.
 */
@Configuration(proxyBeanMethods = false)
class PortfolioConfiguration {
    @Bean
    fun portfolioStore(dsl: DSLContext): PortfolioStore = JooqPortfolioStore(dsl)

    @Bean
    fun getPortfolio(
        authorityReplica: AuthorityReplica,
        portfolioStore: PortfolioStore,
        clock: Clock,
    ) = GetPortfolio(
        PortfolioEntityScope(authorityReplica, CatalogueReadPermissions(), clock, Duration.ofSeconds(1)),
        portfolioStore,
        PORTFOLIO_MAX_LIMIT,
    )

    @Bean
    fun portfolioController(getPortfolio: GetPortfolio) = PortfolioController(getPortfolio)

    companion object {
        /** The `maximum` of the getPortfolio limit parameter in the pinned contract (the generated validation too). */
        const val PORTFOLIO_MAX_LIMIT: Int = 500
    }
}

/**
 * The entities a principal may read the portfolio of: the entities the replica holds a grant on now, kept when
 * [AuthorityReplica.decide] allows the key of the `getPortfolio` route on that entity. The key is read from
 * [CatalogueReadPermissions.ENTRIES], never written again here, and no role name is read (INV-Q-10027). Fail closed
 * (OWASP ASVS V4.1.1): an unmapped route, a malformed tenant id, a denied decision and a thrown exception give no
 * entity.
 */
class PortfolioEntityScope(
    private val replica: AuthorityReplica,
    private val permissions: ReadPermissions,
    private val clock: Clock,
    private val budget: Duration,
) : EntityScope {
    override fun entitiesOf(
        tenantId: String,
        principalId: String,
    ): Set<String> {
        val route = CatalogueReadPermissions.ENTRIES.firstOrNull { it.operationId == OPERATION }
        val key = route?.let { permissions.keyFor(it.method, it.pathTemplate) }
        return if (key == null) {
            emptySet()
        } else {
            runCatching { allowedEntities(tenantId, principalId, key) }.getOrDefault(emptySet())
        }
    }

    private fun allowedEntities(
        tenantId: String,
        principalId: String,
        key: String,
    ): Set<String> {
        val tenant = TenantId.of(tenantId)
        val principal = Principal(principalId, PrincipalKind.HUMAN)
        return replica
            .heldEntities(tenant, principalId)
            .filterTo(mutableSetOf()) { entity ->
                replica
                    .decide(
                        AuthorizationRequest(tenant, principal, entity, key),
                        Deadline.after(budget, clock),
                    ).allowed
            }
    }

    private companion object {
        const val OPERATION = "getPortfolio"
    }
}
