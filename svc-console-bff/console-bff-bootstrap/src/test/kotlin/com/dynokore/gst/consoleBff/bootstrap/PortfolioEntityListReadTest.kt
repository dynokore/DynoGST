package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.authz.ConsoleReadAuthorisationFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioStore
import com.dynokore.gst.consoleBff.application.portfolio.StoredFact
import com.dynokore.gst.consoleBff.application.portfolio.StoredPeriod
import com.dynokore.gst.consoleBff.application.portfolio.StoredReadiness
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * INV-AC-29111: getPortfolio as an entity list read over HTTP: the production read filter in front of the controller
 * and use case wired by [PortfolioConfiguration], a real authority replica and a seeded store of E1, E2 and E3 that
 * records every set of entities it is asked for.
 */
class PortfolioEntityListReadTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-10T10:00:00Z")
    }

    private class RecordingStore(
        private val facts: List<StoredFact>,
    ) : PortfolioStore {
        val asked = mutableListOf<Set<String>>()

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
            asked += entityRefs.toSet()
            return facts.filter { it.period.entityRef in entityRefs }
        }
    }

    private val json = JsonMapper.builder().build()
    private val clock = FixedClock()
    private val tenant = TenantId.of("22222222-2222-2222-2222-222222222222")
    private val readerRole = "entity-list-reader-fixture"
    private val otherRole = "entity-list-other-fixture"
    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { key ->
                when (key) {
                    "period-readiness.view" -> mapOf(readerRole to CellAllowance.PERMITTED)
                    "exception.view" -> mapOf(otherRole to CellAllowance.PERMITTED)
                    else -> emptyMap()
                }
            },
        )

    private fun fact(
        entityRef: String,
        due: String,
    ) = StoredFact(
        StoredPeriod(entityRef, "2026-Q4", PeriodState.DUE, LocalDate.parse(due), null, false),
        null,
    )

    /** E3 has the nearest due date, so a leaked E3 row would lead the page. */
    private val store =
        RecordingStore(
            listOf(fact("E1", "2027-01-31"), fact("E2", "2026-12-15"), fact("E3", "2026-11-30")),
        )

    private var version = 1L

    private fun grant(
        principal: String,
        role: String,
        entityRef: String,
    ) {
        version++
        replica.accept(
            AuthorityChanged(
                tenant,
                principal,
                version,
                AuthorityRecord.RoleGrantRecord(
                    "g-$version",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60)),
                ),
            ),
        )
    }

    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeEach
    fun setUp() {
        replica.onSnapshotComplete(1)
        // ONE_ENTITY's only grant of the portfolio key is on E1; it also holds another key on E3.
        grant(ONE_ENTITY, readerRole, "E1")
        grant(ONE_ENTITY, otherRole, "E3")
        grant(TWO_ENTITIES, readerRole, "E1")
        grant(TWO_ENTITIES, readerRole, "E2")
        grant(OTHER_KEY, otherRole, "E3")
        context = AnnotationConfigApplicationContext()
        context.registerBean("clock", Clock::class.java, Supplier { clock })
        context.registerBean("authorityReplica", AuthorityReplica::class.java, Supplier { replica })
        context.registerBean("dsl", DSLContext::class.java, Supplier { DSL.using(SQLDialect.POSTGRES) })
        context.registerBean(
            "seededPortfolioStore",
            PortfolioStore::class.java,
            Supplier { store },
            BeanDefinitionCustomizer { it.isPrimary = true },
        )
        context.register(PortfolioConfiguration::class.java)
        context.refresh()
    }

    @AfterEach
    fun tearDown() = context.close()

    private fun mvc(): MockMvc =
        MockMvcBuilders
            .standaloneSetup(context.getBean(PortfolioController::class.java))
            .addFilters<StandaloneMockMvcBuilder>(
                ConsoleReadAuthorisationFilter(CatalogueReadPermissions(), replica, clock),
            ).build()

    private fun call(principal: String): MvcResult =
        mvc()
            .perform(
                get("/v1/portfolio")
                    .accept(MediaType.ALL)
                    .requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, principal)
                    .requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, tenant.toString()),
            ).andReturn()

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun rowsOf(result: MvcResult): List<String> {
        result.response.status shouldBe 200
        return body(result)["items"].values().map { it["entityRef"].asString() }
    }

    private fun assertRefused(principal: String) {
        val result = call(principal)
        result.response.status shouldBe 403
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        val refusal = body(result)
        refusal["code"].asString() shouldBe "SRT-004"
        ClosedErrorCode.ACCESS_DENIED.code shouldBe "SRT-004"
        refusal.has("items") shouldBe false
        for (entityRef in listOf("E1", "E2", "E3")) {
            result.response.contentAsString shouldNotContain "\"$entityRef\""
        }
        store.asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-29111 an entity-scoped grant of the key on E1 alone gets exactly the E1 row`() {
        val result = call(ONE_ENTITY)
        rowsOf(result) shouldBe listOf("E1")
        result.response.contentAsString shouldNotContain "E3"
        store.asked shouldBe listOf(setOf("E1"))
    }

    @Test
    fun `INV-AC-29111 entity-scoped grants on E1 and E2 get exactly the rows E1 and E2`() {
        val result = call(TWO_ENTITIES)
        rowsOf(result) shouldBe listOf("E2", "E1")
        result.response.contentAsString shouldNotContain "E3"
        store.asked shouldBe listOf(setOf("E1", "E2"))
    }

    @Test
    fun `INV-AC-29111 an entity-scoped grant of another key only is refused and the store is not read`() {
        assertRefused(OTHER_KEY)
    }

    @Test
    fun `INV-AC-29111 a caller with no grant is refused and the store is not read`() {
        assertRefused(NO_GRANT)
    }

    private companion object {
        const val ONE_ENTITY = "U-one-entity"
        const val TWO_ENTITIES = "U-two-entities"
        const val OTHER_KEY = "U-other-key"
        const val NO_GRANT = "U-no-grant"
    }
}
