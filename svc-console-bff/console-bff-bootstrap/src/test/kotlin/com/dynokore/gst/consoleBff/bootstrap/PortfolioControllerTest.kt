package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.authz.ConsoleReadAuthorisationFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.portfolio.GetPortfolio
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioStore
import com.dynokore.gst.consoleBff.application.portfolio.StoredFact
import com.dynokore.gst.consoleBff.application.portfolio.StoredPeriod
import com.dynokore.gst.consoleBff.application.portfolio.StoredReadiness
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * INV-AC-26282: GET /v1/portfolio over the real read filter, scope, use case and controller, with a seeded in-memory
 * store and a real authority replica; the tenancy edge is a counting fake that must record no call.
 */
class PortfolioControllerTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-10T10:00:00Z")
    }

    /** Fake of the tenancy edge (listPeriodRecords): nothing in the read path may call it. */
    private class TenancyEdge {
        var calls = 0

        @Suppress("unused")
        fun listPeriodRecords(entityRef: String): Nothing {
            calls++
            error("no downstream call: $entityRef")
        }
    }

    private class SeededStore(
        private val facts: List<StoredFact>,
    ) : PortfolioStore {
        var reads = 0

        override fun putPeriods(
            tenantId: String,
            rows: List<StoredPeriod>,
        ) = error("the read never writes")

        override fun putReadiness(
            tenantId: String,
            row: StoredReadiness,
            observedAt: Instant,
        ): Boolean = error("the read never writes")

        override fun facts(
            tenantId: String,
            entityRefs: Set<String>,
        ): List<StoredFact> {
            reads++
            return facts.filter { it.period.entityRef in entityRefs }
        }
    }

    private companion object {
        const val TENANCY_BEAN = "tenancyEdge"
    }

    private val json = JsonMapper.builder().build()
    private val clock = FixedClock()
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val role = "portfolio-fixture-role"
    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { key ->
                if (key == "period-readiness.view") mapOf(role to CellAllowance.PERMITTED) else emptyMap()
            },
        )
    private val tenancy = TenancyEdge()

    private fun period(
        key: Pair<String, String>,
        state: PeriodState,
        due: String,
        filed: String? = null,
        extension: Boolean = false,
    ) = StoredPeriod(key.first, key.second, state, LocalDate.parse(due), filed?.let(LocalDate::parse), extension)

    private fun fact(
        period: StoredPeriod,
        readiness: StoredReadiness? = null,
    ) = StoredFact(period, readiness)

    /** E2 holds the nearest unfiled due date, E1 the later one; E3 is stored but granted to nobody. */
    private val seeded =
        SeededStore(
            listOf(
                fact(period("E1" to "2026-Q3", PeriodState.FILED, "2026-10-31", filed = "2026-10-15")),
                fact(
                    period("E1" to "2026-Q4", PeriodState.DUE, "2027-01-31"),
                    StoredReadiness("E1", "2026-Q4", Verdict.BLOCKED, 2),
                ),
                fact(period("E1" to "2027-Q1", PeriodState.OPEN, "2027-04-30")),
                fact(period("E2" to "2026-Q3", PeriodState.LATE, "2026-10-31", extension = true)),
                fact(
                    period("E2" to "2026-Q4", PeriodState.DUE, "2026-12-15"),
                    StoredReadiness("E2", "2026-Q4", Verdict.READY, null),
                ),
                fact(period("E3" to "2026-Q3", PeriodState.DUE, "2026-10-01")),
            ),
        )

    private fun grant(
        principal: String,
        ref: String,
        scope: GrantScope,
        target: String,
        version: Long,
    ) = replica.accept(
        AuthorityChanged(
            tenant,
            principal,
            version,
            AuthorityRecord.RoleGrantRecord(
                ref,
                RoleGrantState.ACTIVE,
                RoleGrant(role, scope, target, clock.instant().minusSeconds(60)),
            ),
        ),
    )

    private fun seedGrants() {
        replica.onSnapshotComplete(1)
        // P1 holds the key at tenant scope (the route names no entity parameter) and on E1 and E2; P3 on no entity.
        grant("P1", "g-t1", GrantScope.TENANT, tenant.toString(), 2)
        grant("P1", "g-e1", GrantScope.ENTITY, "E1", 3)
        grant("P1", "g-e2", GrantScope.ENTITY, "E2", 4)
        grant("P3", "g-t3", GrantScope.TENANT, tenant.toString(), 5)
    }

    /**
     * The portfolio beans of [PortfolioConfiguration] composed in a Spring context next to the tenancy fake, with the
     * seeded store as the primary [PortfolioStore]; the controller served is the one the context built.
     */
    private fun context(): AnnotationConfigApplicationContext {
        seedGrants()
        val context = AnnotationConfigApplicationContext()
        context.registerBean("clock", Clock::class.java, Supplier { clock })
        context.registerBean("authorityReplica", AuthorityReplica::class.java, Supplier { replica })
        context.registerBean("dsl", DSLContext::class.java, Supplier { DSL.using(SQLDialect.POSTGRES) })
        context.registerBean(TENANCY_BEAN, TenancyEdge::class.java, Supplier { tenancy })
        context.registerBean(
            "seededPortfolioStore",
            PortfolioStore::class.java,
            Supplier { seeded },
            BeanDefinitionCustomizer {
                it.isPrimary =
                    true
            },
        )
        context.register(PortfolioConfiguration::class.java)
        context.refresh()
        return context
    }

    private fun mvc(context: AnnotationConfigApplicationContext = context()): MockMvc =
        MockMvcBuilders
            .standaloneSetup(context.getBean(PortfolioController::class.java))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                ConsoleReadAuthorisationFilter(CatalogueReadPermissions(), replica, clock),
            ).build()

    /** Every bean [name] depends on, directly or through other beans. */
    private fun dependenciesOf(
        context: AnnotationConfigApplicationContext,
        name: String,
        seen: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        for (dependency in context.beanFactory.getDependenciesForBean(name)) {
            if (seen.add(dependency)) dependenciesOf(context, dependency, seen)
        }
        return seen
    }

    private fun call(
        mvc: MockMvc,
        principal: String,
    ): MvcResult =
        mvc
            .perform(
                get("/v1/portfolio")
                    .accept(MediaType.ALL)
                    .requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, principal)
                    .requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, tenant.toString()),
            ).andReturn()

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private val expected: JsonNode =
        json.readTree(
            """
            {"items":[
             {"entityRef":"E2","cells":[
              {"periodRef":"2026-Q3","periodState":"late","extensionGranted":true},
              {"periodRef":"2026-Q4","periodState":"due","extensionGranted":false,"readiness":{"kind":"READY"}}]},
             {"entityRef":"E1","cells":[
              {"periodRef":"2026-Q3","periodState":"filed","extensionGranted":false,"filingDate":"2026-10-15"},
              {"periodRef":"2026-Q4","periodState":"due","extensionGranted":false,
               "readiness":{"kind":"BLOCKED","blockedCount":2}},
              {"periodRef":"2027-Q1","periodState":"open","extensionGranted":false}]}]}
            """.trimIndent(),
        )

    @Test
    fun `INV-AC-26282 a caller with the key for E1 and E2 gets two rows in due date order and the seeded cells`() {
        val result = call(mvc(), "P1")
        result.response.status shouldBe 200
        result.response.contentType shouldContain "application/json"
        body(result) shouldBe expected
    }

    @Test
    fun `INV-AC-26282 no member named score appears in the body`() {
        val text = call(mvc(), "P1").response.contentAsString
        text.lowercase() shouldNotContain "score"
    }

    @Test
    fun `INV-AC-26282 a caller without the key gets the standard refusal and no row`() {
        val result = call(mvc(), "P2")
        result.response.status shouldBe ClosedErrorCode.ACCESS_DENIED.httpStatus
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        val refusal = body(result)
        refusal["code"].asString() shouldBe ClosedErrorCode.ACCESS_DENIED.code
        refusal.has("items") shouldBe false
        result.response.contentAsString shouldNotContain "E1"
        seeded.reads shouldBe 0
    }

    @Test
    fun `INV-AC-26282 the tenancy fake in the context records zero calls and no portfolio bean depends on it`() {
        context().use { context ->
            context.containsBean(TENANCY_BEAN) shouldBe true
            for (name in listOf("portfolioController", "getPortfolio", "portfolioStore")) {
                (TENANCY_BEAN in dependenciesOf(context, name)) shouldBe false
            }
            val mvc = mvc(context)
            call(mvc, "P1").response.status shouldBe 200
            call(mvc, "P2").response.status shouldBe ClosedErrorCode.ACCESS_DENIED.httpStatus
            call(mvc, "P3").response.status shouldBe 200
            tenancy.calls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-26282 an empty grant scope gives 200 with an empty item list`() {
        val result = call(mvc(), "P3")
        result.response.status shouldBe 200
        body(result) shouldBe json.readTree("""{"items":[]}""")
    }
}
