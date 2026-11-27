package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.DrillDownCursors
import com.dynokore.gst.aggregation.adapters.http.IntakeListingCursors
import com.dynokore.gst.aggregation.adapters.permission.EntityViewPermission
import com.dynokore.gst.aggregation.adapters.permission.ReplicaDecision
import com.dynokore.gst.aggregation.adapters.permission.ReplicaEntityViewPermission
import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.ListedIntakeOperation
import com.dynokore.gst.aggregation.listing.ListingDoubles
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.LOOK_ALIKE
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.LOOK_ALIKE_HOLDER
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.TENANT
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.USER_A
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.USER_B
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.VIEW
import com.dynokore.gst.aggregation.listing.ListingDoubles.Companion.VIEWER
import com.dynokore.gst.aggregation.listing.ScriptedPermission
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.core.env.StandardEnvironment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-28766: `listAggregationIntake` over HTTP, through the service's filters, controller, permission, reader and
 * database, composed as TPL-18 writes it. The receipts are rows written by the superuser; every test uses its own
 * submitting principal, so it sees only its own rows.
 */
@Suppress("LargeClass") // one fixture shared by the clauses of the one criterion (CS-50)
class IntakeListingHttpTest {
    private val submitter = "svc-" + UUID.randomUUID()
    private val from = Instant.parse("2027-05-01T00:00:00Z")
    private val to = Instant.parse("2027-05-02T00:00:00Z")
    private val mapper = ObjectMapper()

    /** Numbers the default source keys, so receipts are listed in the order they are seeded (fewer than ten). */
    private var seeded = 0

    @AfterEach
    fun forget() {
        ScriptedPermission.source = null
    }

    /** The members of an item that identify it in these cases. */
    private data class Item(
        val intakeOperation: String,
        val entityRef: String,
        val sourceKey: String,
        val recordedAt: Instant,
    )

    private class Answer(
        val status: Int,
        val body: String,
    )

    private class Served(
        val context: ApplicationContext,
        private val client: HttpClient = HttpClient.newHttpClient(),
    ) {
        fun get(
            query: List<Pair<String, String>>,
            user: String = USER_A,
            path: String = PATH,
        ): Answer {
            val port = context.environment.getProperty("local.server.port")
            val sent = query.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, Charsets.UTF_8)}" }
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$path?$sent"))
                    .header("X-Workload-Token", "token-${ListingDoubles.CALLER}")
                    .header("Authorization", "Bearer $user")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return Answer(response.statusCode(), response.body())
        }
    }

    private fun served(
        dir: Path,
        permission: ((ApplicationContext) -> EntityViewPermission)? = null,
        block: (Served) -> Unit,
    ) {
        ScriptedPermission.source = permission
        val configurations =
            listOfNotNull(ListingDoubles::class.java, permission?.let { ScriptedPermission::class.java })
        AggregationContext
            .startServlet(
                dir,
                configurations,
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=${ListingDoubles.CALLER}",
                    "service-runtime.workload-identity.callers[0].subject=sa-${ListingDoubles.CALLER}",
                ),
                javaClass.classLoader,
            ).use { block(Served(it)) }
    }

    private fun seed(
        entityRef: String,
        at: Instant,
        operation: String = "BATCH",
        sourceKey: String = "K-$submitter-${++seeded}",
        by: String = submitter,
    ): Item {
        AggregationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "insert into aggregation.intake_receipt (tenant_id, intake_operation, entity_ref, source_key, " +
                        "source_revision, aggregate_type, submitted_by, recorded_at) " +
                        "values (?, ?, ?, ?, 7, 'POS', ?, ?)",
                ).use { statement ->
                    listOf(TENANT, operation, entityRef, sourceKey, by).forEachIndexed { index, value ->
                        statement.setObject(index + 1, value)
                    }
                    statement.setTimestamp(6, Timestamp.from(at))
                    statement.executeUpdate()
                }
        }
        return Item(operation, entityRef, sourceKey, at)
    }

    /** Ten receipts in listing order that alternate between the two entities, the unpermitted one first. */
    private fun seedAlternating(): List<Item> =
        (0 until TEN).map {
            seed(
                if (it % 2 == 0) OTHER else HELD,
                from.plusSeconds(it.toLong()),
                if (it % 3 == 0) "BATCH" else "TRANSACTIONS",
                "K-$it-$submitter",
            )
        }

    /** The context's own replica, synced, holding for each of [users] one grant of [role] on each of [entities]. */
    private fun grant(
        served: Served,
        role: String,
        vararg entities: String,
        users: List<String> = listOf(USER_A),
    ) {
        val replica = served.context.getBean(AuthorityReplica::class.java)
        replica.onSnapshotComplete(0)
        var version = 0L
        users.forEach { user ->
            entities.forEach { entityRef ->
                val held = RoleGrant(role, GrantScope.ENTITY, entityRef, Instant.now().minusSeconds(60))
                replica.accept(
                    AuthorityChanged(
                        TenantId(TENANT),
                        user,
                        ++version,
                        AuthorityRecord.RoleGrantRecord("grant-$user-$entityRef", RoleGrantState.ACTIVE, held),
                    ),
                )
            }
        }
    }

    private fun query(
        limit: String? = null,
        cursor: String? = null,
        submittedBy: String = submitter,
        recordedFrom: String = from.toString(),
        recordedTo: String = to.toString(),
    ): List<Pair<String, String>> =
        listOfNotNull(
            "submittedBy" to submittedBy,
            "recordedFrom" to recordedFrom,
            "recordedTo" to recordedTo,
            limit?.let { "limit" to it },
            cursor?.let { "cursor" to it },
        )

    private fun page(answer: Answer): Pair<List<Item>, String?> {
        withClue(answer.body) { answer.status shouldBe 200 }
        val body = mapper.readTree(answer.body)
        return body["items"].toList().map(::item) to body["nextCursor"]?.asString()
    }

    private fun item(node: JsonNode): Item {
        node.propertyNames().toSet() shouldBe MEMBERS
        node["sourceRevision"].asInt() shouldBe 7
        node["aggregateType"].asString() shouldBe "POS"
        node["aggregateIds"].size() shouldBe 0
        return Item(
            node["intakeOperation"].asString(),
            node["entityRef"].asString(),
            node["sourceKey"].asString(),
            Instant.parse(node["recordedAt"].asString()),
        )
    }

    private fun refusal(
        answer: Answer,
        status: Int,
        code: String,
        label: String,
    ) {
        withClue("$label: ${answer.body}") {
            answer.status shouldBe status
            val body = mapper.readTree(answer.body)
            body["code"].asString() shouldBe code
            body.has("items") shouldBe false
            body.has("nextCursor") shouldBe false
        }
    }

    /** All pages of [limit] items, following `nextCursor` until the answer omits it. */
    private fun Served.pages(limit: Int): List<List<Item>> {
        val pages = mutableListOf<List<Item>>()
        var cursor: String? = null
        do {
            val (items, next) = page(get(query(limit = limit.toString(), cursor = cursor)))
            pages += items
            cursor = next
        } while (cursor != null && pages.size <= TEN)
        return pages
    }

    @Test
    fun `INV-AC-28766 only the permitted entity is listed, filtered before paging, with its intake operation`(
        @TempDir dir: Path,
    ) {
        val permitted = seedAlternating().filter { it.entityRef == HELD }
        seed(HELD, from, by = submitter + "0")
        permitted.map { it.intakeOperation }.toSet() shouldBe setOf("BATCH", "TRANSACTIONS")
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            // limit 2 is below the ten rows and below the five permitted ones: every page is full of permitted rows
            service.pages(2) shouldBe permitted.chunked(2)
            service.pages(1).flatten() shouldBe permitted
            // a page that ends exactly at the last permitted row carries no cursor
            page(service.get(query(limit = "5"))) shouldBe (permitted to null)
            page(service.get(query())) shouldBe (permitted to null)
            page(service.get(query(limit = "500"))) shouldBe (permitted to null)
        }
    }

    @Test
    fun `INV-AC-28766 two items that differ only in the intake operation are each listed once across pages`(
        @TempDir dir: Path,
    ) {
        val key = "SHARED-$submitter"
        val transactions = seed(HELD, from, "TRANSACTIONS", key)
        val batch = seed(HELD, from, "BATCH", key)
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            service.pages(1) shouldBe listOf(listOf(batch), listOf(transactions))
        }
    }

    @Test
    fun `INV-AC-28766 a caller permitted for no entity of the window gets the answer of an empty window`(
        @TempDir dir: Path,
    ) {
        seedAlternating()
        served(dir) { service ->
            grant(service, VIEWER, "E-ELSEWHERE")
            val none = service.get(query(limit = "2"))
            val empty = service.get(query(limit = "2", submittedBy = "$submitter-recorded-nothing"))
            withClue(none.body) { none.status shouldBe 200 }
            none.body shouldBe """{"items":[]}"""
            (empty.status to empty.body) shouldBe (none.status to none.body)
        }
    }

    @Test
    fun `INV-AC-28766 the look-alike key submission view-statuses granted alone gets 200 with no item`(
        @TempDir dir: Path,
    ) {
        seedAlternating()
        served(dir) { service ->
            grant(service, LOOK_ALIKE_HOLDER, HELD, OTHER)
            page(service.get(query())) shouldBe (emptyList<Item>() to null)
            // the grant is live: the same replica permits the look-alike key
            val control =
                AuthorizationRequest(TenantId(TENANT), Principal(USER_A, PrincipalKind.HUMAN), HELD, LOOK_ALIKE)
            service.context
                .getBean(AuthorityReplica::class.java)
                .decide(control, Deadline.after(Duration.ofSeconds(1), Clock.systemUTC()))
                .allowed shouldBe true
        }
    }

    /** The service's own permission implementation over [decision] instead of the bound replica. */
    private fun over(decision: ReplicaDecision): (ApplicationContext) -> EntityViewPermission =
        { context ->
            ReplicaEntityViewPermission(
                decision,
                context.getBean(TenantContext::class.java),
                Clock.systemUTC(),
                Duration.ofSeconds(1),
            )
        }

    /** Every answer of `decide`, or its failure, that is no decision of this replica. */
    private val unavailable: Map<String, () -> AuthorityDecision> =
        mapOf(
            "degraded" to {
                AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
            },
            "degraded allow" to {
                AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
            },
            "fallback allow" to { AuthorityDecision(true, DecisionSource.FALLBACK, DecisionReason.GRANTED) },
            "fallback deny" to { AuthorityDecision(false, DecisionSource.FALLBACK, DecisionReason.NO_GRANT) },
            "local without authority" to {
                AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.AUTHORITY_UNAVAILABLE)
            },
            "decide throws" to { error("replica failed") },
        )

    @Test
    fun `INV-AC-28766 an unavailable decision for one of two entities refuses the request with 403 and no item`(
        @TempDir dir: Path,
    ) {
        val rows = seedAlternating()
        val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
        var broken: String? = null
        var answer: () -> AuthorityDecision = { granted }
        val asked = mutableListOf<AuthorizationRequest>()
        val decision =
            ReplicaDecision { request, _ ->
                asked += request
                if (request.entityRef == broken) answer() else granted
            }
        served(dir, over(decision)) { service ->
            // control: with both decisions available both entities are listed, each entity decided once
            val (first, cursor) = page(service.get(query(limit = "4")))
            first shouldBe rows.take(4)
            asked.map { it.entityRef }.sorted() shouldBe listOf(HELD, OTHER)
            listOf(HELD, OTHER).forEach { entity ->
                unavailable.forEach { (label, failure) ->
                    broken = entity
                    answer = failure
                    refusal(service.get(query()), 403, "SRT-004", "$label for $entity")
                    refusal(service.get(query(limit = "1")), 403, "SRT-004", "$label for $entity, page of 1")
                    // a cursor carries no permission: the next page is decided again
                    refusal(service.get(query(cursor = cursor)), 403, "SRT-004", "$label for $entity, next page")
                }
            }
        }
        asked.map { Triple(it.principal.id, it.principal.kind, it.action) }.toSet() shouldBe
            setOf(Triple(USER_A, PrincipalKind.HUMAN, VIEW))
        asked.map { it.tenant }.toSet() shouldBe setOf(TenantId(TENANT))
    }

    @Test
    fun `INV-AC-28766 a permission revoked between two pages is not carried by the cursor`(
        @TempDir dir: Path,
    ) {
        seed(HELD, from)
        seed(HELD, from.plusSeconds(1))
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            val cursor = checkNotNull(page(service.get(query(limit = "1"))).second)
            val revoked = RoleGrant(VIEWER, GrantScope.ENTITY, HELD, Instant.now().minusSeconds(60))
            service.context.getBean(AuthorityReplica::class.java).accept(
                AuthorityChanged(
                    TenantId(TENANT),
                    USER_A,
                    2,
                    AuthorityRecord.RoleGrantRecord("grant-$USER_A-$HELD", RoleGrantState.REMOVED, revoked),
                ),
            )
            page(service.get(query(limit = "1", cursor = cursor))) shouldBe (emptyList<Item>() to null)
        }
    }

    @Test
    fun `INV-AC-28766 the service's own replica before its first sync gives no decision and the request is 403`(
        @TempDir dir: Path,
    ) {
        seed(HELD, from)
        served(dir) { service -> refusal(service.get(query()), 403, "SRT-004", "replica not synced") }
    }

    @Test
    fun `INV-AC-28766 with no replica bound the request is 403 with no item, an empty window too`(
        @TempDir dir: Path,
    ) {
        seed(HELD, from)
        // the service's own wiring over a factory that holds no AuthorityReplica
        val unbound = { context: ApplicationContext ->
            PermissionConfiguration().entityViewPermission(
                DefaultListableBeanFactory().getBeanProvider(AuthorityReplica::class.java),
                context.getBean(TenantContext::class.java),
                DefaultListableBeanFactory().getBeanProvider(Clock::class.java),
                StandardEnvironment(),
            )
        }
        served(dir, unbound) { service ->
            refusal(service.get(query()), 403, "SRT-004", "no replica")
            refusal(
                service.get(query(submittedBy = "$submitter-recorded-nothing")),
                403,
                "SRT-004",
                "no replica, empty window",
            )
        }
    }

    @Test
    fun `INV-AC-28766 a missing parameter, an empty or inverted window and a broken limit are 400 SRT-001`(
        @TempDir dir: Path,
    ) {
        val only = seed(HELD, from)
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            listOf("submittedBy", "recordedFrom", "recordedTo").forEach { missing ->
                val sent = query().filterNot { it.first == missing }
                refusal(service.get(sent), 400, "SRT-001", "missing $missing")
            }
            mapOf(
                "recordedFrom equal to recordedTo" to query(recordedTo = from.toString()),
                "the same instant in another offset" to query(recordedTo = "2027-05-01T08:00:00+08:00"),
                "recordedFrom after recordedTo" to query(recordedFrom = to.toString(), recordedTo = from.toString()),
                "recordedFrom no date-time" to query(recordedFrom = "2027-05-01"),
                "a five-digit year" to query(recordedTo = "+10000-01-01T00:00:00Z"),
                "submittedBy too long" to query(submittedBy = "p".repeat(65)),
                "limit above 500" to query(limit = "501"),
                "limit 0" to query(limit = "0"),
                "limit below 0" to query(limit = "-1"),
                "limit no number" to query(limit = "ten"),
                "cursor empty" to query(cursor = ""),
                "cursor not issued" to query(cursor = "abc.def"),
            ).forEach { (label, sent) -> refusal(service.get(sent), 400, "SRT-001", label) }
            page(service.get(query())) shouldBe (listOf(only) to null)
        }
    }

    @Test
    fun `INV-AC-28766 a cursor serves only the filters it was issued for and only unaltered and unexpired`(
        @TempDir dir: Path,
    ) {
        val first = seed(HELD, from)
        val second = seed(HELD, from.plusSeconds(1))
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            val (items, issued) = page(service.get(query(limit = "1")))
            items shouldBe listOf(first)
            val cursor = checkNotNull(issued)
            page(service.get(query(limit = "1", cursor = cursor))).first shouldBe listOf(second)
            // the same instants written in another offset are the same filter
            val sameWindow = query(cursor = cursor, recordedFrom = "2027-05-01T08:00:00+08:00")
            page(service.get(sameWindow)).first shouldBe listOf(second)
            val (body, tag) = cursor.split('.')
            val forge = Forge(service.context.getBean(SecretStore::class.java), first)
            // control: a cursor sealed here 23 hours ago for this scope is served
            page(service.get(query(cursor = forge.listing(Duration.ofHours(23))))).first shouldBe listOf(second)
            mapOf(
                "other submitter" to query(cursor = cursor, submittedBy = submitter + "0"),
                "other recordedFrom" to query(cursor = cursor, recordedFrom = from.minusSeconds(1).toString()),
                "other recordedTo" to query(cursor = cursor, recordedTo = to.plusSeconds(1).toString()),
                "altered tag" to query(cursor = "$body.${altered(tag)}"),
                "altered payload" to query(cursor = "${altered(body)}.$tag"),
                "payload cut" to query(cursor = "${body.dropLast(4)}.$tag"),
                "tag cut" to query(cursor = body),
                "a third part" to query(cursor = "$cursor.x"),
                "another tenant's" to query(cursor = forge.listing(Duration.ZERO, tenant = "other")),
                "expired" to query(cursor = forge.listing(Duration.ofHours(25))),
                "a drill-down cursor" to query(cursor = forge.drillDown()),
            ).forEach { (label, sent) -> refusal(service.get(sent), 400, "SRT-001", label) }
            // and a cursor of this listing is no cursor of the drill-down, which shares the key
            val onDrillDown =
                service.get(
                    listOf("aggregateId" to UUID.randomUUID().toString(), "limit" to "1", "cursor" to cursor),
                    path = DRILL_DOWN_PATH,
                )
            refusal(onDrillDown, 400, "SRT-001", "a listing cursor on the drill-down")
        }
    }

    /** Cursors sealed with the service's own key, for what no answer of the service can be made to carry. */
    private inner class Forge(
        private val secrets: SecretStore,
        private val after: Item,
    ) {
        /** The cursor of `user-a` for this window after [after], issued [age] ago. */
        fun listing(
            age: Duration,
            tenant: String = TenantId(TENANT).toString(),
        ): String =
            IntakeListingCursors(secrets, Clock.fixed(Instant.now().minus(age), ZoneOffset.UTC)).encode(
                IntakeListingPosition(after.sourceKey, from, 7, HELD, ListedIntakeOperation.BATCH),
                IntakeListingCursors.Scope(tenant, USER_A, IntakeListingFilter(submitter, from, to)),
            )

        fun drillDown(): String =
            DrillDownCursors(secrets, Clock.systemUTC()).encode(
                DrillDownPosition("U", "T"),
                DrillDownCursors.Scope(TenantId(TENANT).toString(), USER_A, submitter),
            )
    }

    @Test
    fun `INV-AC-28766 a cursor serves only the principal it was issued to`(
        @TempDir dir: Path,
    ) {
        val first = seed(HELD, from)
        val second = seed(HELD, from.plusSeconds(1))
        served(dir) { service ->
            grant(service, VIEWER, HELD, users = listOf(USER_A, USER_B))
            val issuedToA = checkNotNull(page(service.get(query(limit = "1"))).second)
            val asB = service.get(query(limit = "1", cursor = issuedToA), USER_B)
            refusal(asB, 400, "SRT-001", "user-a's cursor sent by user-b")
            // control: user-b is permitted and pages with a cursor of their own
            val (items, issuedToB) = page(service.get(query(limit = "1"), USER_B))
            items shouldBe listOf(first)
            val own = query(limit = "1", cursor = checkNotNull(issuedToB))
            page(service.get(own, USER_B)) shouldBe (listOf(second) to null)
        }
    }

    @Test
    fun `INV-AC-28766 with the cursor key secret absent no page carries a nextCursor`(
        @TempDir dir: Path,
    ) {
        val rows = listOf(seed(HELD, from), seed(HELD, from.plusSeconds(1)))
        served(dir) { service ->
            grant(service, VIEWER, HELD)
            val cursor = checkNotNull(page(service.get(query(limit = "1"))).second)
            Files.delete(dir.resolve("aggregation/drill-down-cursor-key"))
            listOf(query(limit = "1"), query(limit = "1", cursor = cursor)).forEach {
                refusal(service.get(it), 500, "SRT-010", "no cursor key")
            }
            // a page that needs no cursor is still served, and carries none
            page(service.get(query())) shouldBe (rows to null)
        }
    }

    /** [text] with one character well inside it changed, so its decoded bytes differ. */
    private fun altered(text: String): String {
        val at = text.length / 2
        val other = if (text[at] == 'A') 'B' else 'A'
        return text.substring(0, at) + other + text.substring(at + 1)
    }

    private companion object {
        const val PATH = "/internal/v1/aggregation/intake-receipts"
        const val DRILL_DOWN_PATH = "/internal/v1/aggregation/underlying-transactions"
        const val HELD = "E-001"
        const val OTHER = "E-002"
        const val TEN = 10
        val MEMBERS =
            setOf(
                "intakeOperation",
                "entityRef",
                "sourceKey",
                "sourceRevision",
                "aggregateType",
                "recordedAt",
                "aggregateIds",
            )
    }
}
