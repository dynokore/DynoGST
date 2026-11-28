package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionQueue
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptions
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptionsQuery
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptionsResult
import com.dynokore.gst.consoleBff.application.exceptions.QueuedException
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-29110: a read marked as an entity list read admits a caller allowed on at least one entity. */
class EntityListReadAdmissionTest {
    private class FixedClock : Clock() {
        /** When set, a read of the time made inside `heldEntities` throws. */
        var failHeldEntities = false

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant {
            val inScan =
                failHeldEntities &&
                    StackWalker.getInstance().walk { frames ->
                        // Kotlin mangles the name (`heldEntities-<hash>`) as TenantId is a value class
                        frames.anyMatch { it.methodName.substringBefore('-') == "heldEntities" }
                    }
            check(!inScan) { "held entities unavailable" }
            return Instant.parse("2026-11-27T10:00:00Z")
        }
    }

    /** Holds one row of E1 and one of E2, answers only the rows of the asked entities and records each asked set. */
    private class RecordingQueue : ExceptionQueue {
        val asked = mutableListOf<Set<String>>()
        private val rows = listOf(row(1, "E1"), row(2, "E2"))

        override fun page(
            tenantId: String,
            entityRefs: Set<String>,
            state: ItemState?,
            deferred: Boolean,
            after: ExceptionSortKey?,
            limit: Int,
        ): List<QueuedException> {
            asked += entityRefs
            return rows.filter { it.row.entityRef in entityRefs }.take(limit)
        }

        private fun row(
            n: Int,
            entity: String,
        ) = QueuedException(
            ExceptionRow(
                "T1",
                UUID.nameUUIDFromBytes("x$n".toByteArray()),
                entity,
                "doc-$n",
                "MDM-001",
                "Finance",
                "FIX",
                ItemState.OPEN,
                Instant.parse("2026-11-01T08:0$n:00Z"),
                "",
                n.toLong(),
            ),
            LocalDate.parse("2026-12-0$n"),
        )
    }

    private class Outcome(
        val response: MockHttpServletResponse,
        val chainCalls: Int,
        val results: List<ListExceptionsResult>,
    )

    private val clock = FixedClock()
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val roles =
        OperationRoleMap { key ->
            when (key) {
                "exception.view" -> mapOf(VIEW_ROLE to CellAllowance.PERMITTED)
                "period-readiness.view" -> mapOf(OTHER_ROLE to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }
    private val replica = AuthorityReplica(clock, roles)
    private val queue = RecordingQueue()
    private var version = 1L

    private fun caughtUp(subject: AuthorityReplica = replica) = subject.onSnapshotComplete(1)

    private fun grant(
        scope: GrantScope,
        ref: String,
        role: String = VIEW_ROLE,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        subject: AuthorityReplica = replica,
    ) = subject.accept(
        AuthorityChanged(
            tenant,
            PRINCIPAL,
            ++version,
            AuthorityRecord.RoleGrantRecord(
                "g-$role-$ref",
                state,
                RoleGrant(role, scope, ref, Instant.parse("2026-11-27T09:00:00Z")),
            ),
        ),
    )

    private fun get(
        method: String,
        entityRef: String? = null,
        subject: AuthorityReplica = replica,
        permissions: ReadPermissions = CatalogueReadPermissions(),
    ): Outcome {
        val request = MockHttpServletRequest(method, PATH).apply { requestURI = PATH }
        request.setAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, PRINCIPAL)
        request.setAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE, tenant.toString())
        entityRef?.let { request.addParameter("entityRef", it) }
        val response = MockHttpServletResponse()
        val list =
            ListExceptions(
                ReplicaEntityScope(subject, CatalogueReadPermissions(), clock, BUDGET),
                queue,
                MAX_LIMIT,
            )
        var calls = 0
        val results = mutableListOf<ListExceptionsResult>()
        ConsoleReadAuthorisationFilter(permissions, subject, clock, BUDGET).doFilter(request, response) { _, _ ->
            calls++
            results +=
                list.list(
                    ListExceptionsQuery(
                        tenant.toString(),
                        PRINCIPAL,
                        request.getParameter("entityRef"),
                        null,
                        false,
                        MAX_LIMIT,
                        null,
                    ),
                )
        }
        return Outcome(response, calls, results)
    }

    private fun admitted(out: Outcome): ListExceptionsResult {
        out.response.status shouldBe 200
        out.chainCalls shouldBe 1
        return out.results.single()
    }

    private fun refused(out: Outcome) {
        out.response.status shouldBe 403
        out.response.contentAsString shouldContain "SRT-004"
        out.chainCalls shouldBe 0
    }

    private fun refusedOnBothMethods(
        subject: AuthorityReplica = replica,
        permissions: ReadPermissions = CatalogueReadPermissions(),
    ) {
        for (method in METHODS) refused(get(method, subject = subject, permissions = permissions))
        queue.asked shouldBe emptyList()
    }

    private fun entitiesListed(result: ListExceptionsResult): List<String> =
        (result as ListExceptionsResult.Listed).items.map { it.row.entityRef }

    @Test
    fun `INV-AC-29110 an entity-scope grant on E1 passes and lists the E1 rows and no E2 row`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        for (method in METHODS) entitiesListed(admitted(get(method))) shouldBe listOf("E1")
        queue.asked shouldBe listOf(setOf("E1"), setOf("E1"))
    }

    @Test
    fun `INV-AC-29110 an entity-scope grant on E1 asking for E2 passes and is out of scope with no row`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        for (method in METHODS) admitted(get(method, "E2")) shouldBe ListExceptionsResult.OutOfScope("E2")
        queue.asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-29110 one allowed entity among held entities of other keys is enough`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E2", OTHER_ROLE)
        grant(GrantScope.ENTITY, "E1")
        for (method in METHODS) entitiesListed(admitted(get(method))) shouldBe listOf("E1")
    }

    @Test
    fun `INV-AC-29110 a tenant-scope grant passes as before when no entity is held`() {
        caughtUp()
        grant(GrantScope.TENANT, tenant.toString())
        replica.heldEntities(tenant, PRINCIPAL) shouldBe emptySet()
        for (method in METHODS) entitiesListed(admitted(get(method))) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-29110 a caller with no grant is refused`() {
        caughtUp()
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 an entity-scope grant of another key only is refused`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1", OTHER_ROLE)
        replica.heldEntities(tenant, PRINCIPAL) shouldBe setOf("E1")
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 a removed grant is refused on the next read`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        admitted(get("GET"))
        grant(GrantScope.ENTITY, "E1", state = RoleGrantState.REMOVED)
        queue.asked.clear()
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 a revoked principal is refused on the next read`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        admitted(get("GET"))
        replica.accept(
            AuthorityChanged(tenant, PRINCIPAL, ++version, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
        queue.asked.clear()
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 beyond the lag bound with no allowing decision an entity grant holder is refused`() {
        // never caught up and no fallback: every decide of the scan is a degraded refusal
        grant(GrantScope.ENTITY, "E1")
        replica.heldEntities(tenant, PRINCIPAL) shouldBe setOf("E1")
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 the whole scan is asked with the route key under one deadline`() {
        val asked = mutableListOf<Pair<String, Deadline>>()
        val denying =
            AuthorityReplica(
                clock,
                roles,
                fallback =
                    ReplicaFallback { request, deadline ->
                        asked += request.action to deadline
                        FallbackResult.Found(AuthorityVerdict(false))
                    },
            )
        grant(GrantScope.ENTITY, "E1", subject = denying)
        grant(GrantScope.ENTITY, "E2", subject = denying)
        refused(get("GET", subject = denying))
        asked.map { it.first } shouldBe listOf("exception.view", "exception.view")
        (asked[0].second === asked[1].second) shouldBe true
    }

    @Test
    fun `INV-AC-29110 the scan stops at the first allowing decision`() {
        val asked = mutableListOf<String>()
        val allowing =
            AuthorityReplica(
                clock,
                roles,
                fallback =
                    ReplicaFallback { request, _ ->
                        asked += request.action
                        FallbackResult.Found(AuthorityVerdict(true))
                    },
            )
        grant(GrantScope.ENTITY, "E1", subject = allowing)
        grant(GrantScope.ENTITY, "E2", subject = allowing)
        val request = MockHttpServletRequest("GET", PATH).apply { requestURI = PATH }
        request.setAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, PRINCIPAL)
        request.setAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE, tenant.toString())
        var calls = 0
        // a chain that reads nothing, so every question the fallback sees is the filter's
        ConsoleReadAuthorisationFilter(CatalogueReadPermissions(), allowing, clock, BUDGET)
            .doFilter(request, MockHttpServletResponse()) { _, _ -> calls++ }
        calls shouldBe 1
        asked shouldHaveSize 1
    }

    @Test
    fun `INV-AC-29110 a decide of the scan that throws is refused`() {
        var questions = 0
        val failing =
            AuthorityReplica(
                clock,
                OperationRoleMap { key ->
                    questions++
                    check(questions == 1) { "role map unavailable" }
                    roles.rolesFor(key)
                },
            )
        caughtUp(failing)
        grant(GrantScope.ENTITY, "E1", subject = failing)
        refused(get("GET", subject = failing))
        questions shouldBe 2
        questions = 0
        refused(get("HEAD", subject = failing))
        questions shouldBe 2
        queue.asked shouldBe emptyList()
    }

    @Test
    fun `INV-AC-29110 held entities that throw are refused`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        clock.failHeldEntities = true
        shouldThrow<IllegalStateException> { replica.heldEntities(tenant, PRINCIPAL) }
        refusedOnBothMethods()
    }

    @Test
    fun `INV-AC-29110 a route whose entry is not marked refuses an entity-scope grant alone`() {
        caughtUp()
        grant(GrantScope.ENTITY, "E1")
        val cleared =
            CatalogueReadPermissions(
                entries = CatalogueReadPermissions.ENTRIES.map { it.copy(entityListRead = false) },
            )
        refusedOnBothMethods(permissions = cleared)
        grant(GrantScope.TENANT, tenant.toString())
        admitted(get("GET", permissions = cleared))
    }

    @Test
    fun `INV-AC-29110 only the marked table entry admits any entity`() {
        val table = CatalogueReadPermissions()
        table.admitsAnyEntityFor("GET", PATH) shouldBe true
        table.admitsAnyEntityFor("POST", PATH) shouldBe false
        table.admitsAnyEntityFor("GET", "/v1/portfolio") shouldBe true
        for (path in listOf(
            "/bff/entities/E1/rendition-evidence",
            "/bff/configuration-bundles/b1/promotion-diff",
            "/v1/exceptions/",
            "/v1/exceptions/x",
            "/V1/exceptions",
        )) {
            table.admitsAnyEntityFor("GET", path) shouldBe false
        }
        val unchanged = ReadPermissions { _, _ -> "exception.view" }
        unchanged.admitsAnyEntityFor("GET", PATH) shouldBe false
    }

    @Test
    fun `INV-AC-29110 a marked entry that names an entity parameter or path variable cannot be constructed`() {
        val plain = CatalogueReadPermissions.Entry("GET", "/x/{id}", "fixtureOp", "exception.view")
        plain.copy(entityListRead = true).entityListRead shouldBe true
        for (named in listOf(plain.copy(entityParameter = "entity"), plain.copy(entityPathVariable = "id"))) {
            shouldThrow<IllegalArgumentException> { named.copy(entityListRead = true) }
        }
        shouldThrow<IllegalArgumentException> {
            CatalogueReadPermissions.Entry(
                "GET",
                "/x",
                "fixtureOp",
                "exception.view",
                entityParameter = "entity",
                entityListRead = true,
            )
        }
        shouldThrow<IllegalArgumentException> {
            CatalogueReadPermissions.Entry(
                "GET",
                "/x/{id}",
                "fixtureOp",
                "exception.view",
                entityPathVariable = "id",
                entityListRead = true,
            )
        }
    }

    private companion object {
        const val PATH = "/v1/exceptions"
        const val PRINCIPAL = "p1"
        const val VIEW_ROLE = "fixture-viewer"
        const val OTHER_ROLE = "fixture-other"
        const val MAX_LIMIT = 50
        val METHODS = listOf("GET", "HEAD")
        val BUDGET: Duration = Duration.ofSeconds(1)
    }
}
