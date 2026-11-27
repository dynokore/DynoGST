package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.permission.EntityViewPermission
import com.dynokore.gst.canonical.adapters.permission.ReplicaDecision
import com.dynokore.gst.canonical.adapters.permission.ReplicaEntityViewPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.StandardEnvironment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * INV-AC-28718: `listIngestedDocuments` over HTTP, through the service's filters, controller, reads, database and
 * authority replica. The caller is user-a of tenant-a unless a test names another; every test records under a principal of its own. The role
 * names of [roles] are fixture data: the service only ever asks for the permission key.
 */
@Suppress("LargeClass") // one fixture shared by the clauses of the one criterion (CS-50)
class ListIngestedDocumentsHttpTest {
    private val doubles = WorkloadDoubles(callersOf(ContractDocument.operations(ContractDocument.text())))
    private val tenant = EndUserDoubles.tenantA
    private val principal = "principal-" + UUID.randomUUID()
    private val from = Instant.parse("2026-05-01T00:00:00Z")
    private val to = Instant.parse("2026-05-02T00:00:00Z")
    private val json = JsonMapper.builder().build()

    private val roles =
        OperationRoleMap { key ->
            when (key) {
                VIEW -> mapOf(VIEWER to CellAllowance.PERMITTED)
                LOOK_ALIKE -> mapOf(LOOK_ALIKE_HOLDER to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }

    /** One recorded version as the listing shows it. */
    private data class Item(
        val entityRef: String,
        val sourceSystem: String,
        val sourceKey: String,
        val sourceRevision: Int,
        val docUuid: String,
        val version: Int,
        val recordedAt: Instant,
    )

    private fun execute(sql: String) {
        CanonicalDb.superuser().use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    /** One document of [entityRef] with one version [by] recorded at [at], linked to its own source event. */
    private fun seed(
        entityRef: String?,
        at: Instant,
        by: String = principal,
    ): Item {
        val doc = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val key = "key-$doc"
        val owner = tenant.value
        val ref = entityRef?.let { "'$it'" } ?: "null"
        execute(
            "insert into canonical.source_event (tenant_id, source_system, source_key, source_revision, doc_uuid, " +
                "result) values ('$owner', 'erp-a', '$key', 3, '$doc', '{}'::jsonb)",
        )
        execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, entity_ref, direction, " +
                "document_type_code, document_number, issuer_party_key, fiscal_year, partition_date, " +
                "transaction_date, current_version, state) values ('$doc', '$owner', '$entity', $ref, 'SALE', 380, " +
                "'N-$doc', 'i', 2026, '2026-03-15', '2026-03-15', 1, 'READY')",
        )
        execute(
            "insert into canonical.document_version (doc_uuid, version, tenant_id, entity_id, partition_date, " +
                "canonical, scope_verdict, scope_pack_version, mapping_pack_version, specification_version, " +
                "created_at, created_by, source_system, source_key, source_revision) values ('$doc', 1, '$owner', " +
                "'$entity', '2026-03-15', '{}'::jsonb, 'IN_SCOPE', 's', 'm', 'PINT_SG', '$at', '$by', " +
                "'erp-a', '$key', 3)",
        )
        return Item(entityRef.orEmpty(), "erp-a", key, 3, doc.toString(), 1, at)
    }

    /** Ten versions in listing order that alternate between the two entities, the denied one first. */
    private fun seedAlternating(): List<Item> =
        (0 until TEN).map { seed(if (it % 2 == 0) OTHER else HELD, from.plusSeconds(it.toLong())) }

    /** The context's own replica, synced, holding for each of [users] one grant of [role] on each of [entities]. */
    private fun grant(
        service: ServedService,
        role: String,
        vararg entities: String,
        users: List<String> = listOf("user-a"),
    ) {
        val replica = service.context.getBean(AuthorityReplica::class.java)
        replica.onSnapshotComplete(0)
        users.forEach { user ->
            entities.forEachIndexed { index, entityRef ->
                val held = RoleGrant(role, GrantScope.ENTITY, entityRef, Instant.now().minusSeconds(60))
                replica.accept(
                    AuthorityChanged(
                        tenant,
                        user,
                        index + 1L,
                        AuthorityRecord.RoleGrantRecord("grant-$user-$index", RoleGrantState.ACTIVE, held),
                    ),
                )
            }
        }
    }

    private fun served(
        permission: ((ServedService) -> EntityViewPermission)? = null,
        block: (ServedService) -> Unit,
    ) {
        lateinit var running: ServedService
        ServedService.run(doubles, customize = { runner ->
            val mapped = runner.withBean("fixtureRoles", OperationRoleMap::class.java, Supplier { roles })
            if (permission == null) {
                mapped
            } else {
                mapped.withBean(
                    EntityViewPermission::class.java,
                    Supplier {
                        EntityViewPermission { actor, refs -> permission(running).permittedAmong(actor, refs) }
                    },
                )
            }
        }) { service ->
            running = service
            block(service)
        }
    }

    private fun query(
        limit: String? = null,
        cursor: String? = null,
        submittedBy: String = principal,
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

    private fun ServedService.list(
        query: List<Pair<String, String>>,
        user: String = EndUserDoubles.USER_A,
    ): Answer = get(PATH, query, doubles.tokenOf("console-bff"), user)

    private fun page(answer: Answer): Pair<List<Item>, String?> {
        assertThat(answer.status).describedAs(answer.body).isEqualTo(200)
        val body = json.readTree(answer.body)
        assertThat(body.propertyNames()).isSubsetOf("items", "nextCursor")
        return body.get("items").values().map(::item) to body.get("nextCursor")?.asString()
    }

    private fun item(node: JsonNode): Item {
        assertThat(node.propertyNames()).containsExactlyInAnyOrder(
            "entityRef",
            "sourceSystem",
            "sourceKey",
            "sourceRevision",
            "docUuid",
            "version",
            "recordedAt",
        )
        return Item(
            node.get("entityRef").asString(),
            node.get("sourceSystem").asString(),
            node.get("sourceKey").asString(),
            node.get("sourceRevision").asInt(),
            node.get("docUuid").asString(),
            node.get("version").asInt(),
            Instant.parse(node.get("recordedAt").asString()),
        )
    }

    private fun assertProblem(
        answer: Answer,
        status: Int,
        code: String,
        label: String,
    ) {
        assertThat(answer.status).describedAs("$label: ${answer.body}").isEqualTo(status)
        val body = json.readTree(answer.body)
        assertThat(body.get("code").asString()).describedAs(label).isEqualTo(code)
        assertThat(body.has("items")).describedAs(label).isFalse()
    }

    /** All pages of [limit] items, following `nextCursor` until the answer omits it. */
    private fun ServedService.pages(limit: Int): List<List<Item>> {
        val pages = mutableListOf<List<Item>>()
        var cursor: String? = null
        do {
            val (items, next) = page(list(query(limit = limit.toString(), cursor = cursor)))
            pages += items
            cursor = next
        } while (cursor != null && pages.size <= TEN)
        return pages
    }

    @Test
    fun `INV-AC-28718 only the permitted entity is listed with its fields and every page is full of permitted rows`() {
        val permitted = seedAlternating().filter { it.entityRef == HELD }
        seed(HELD, from, by = principal + "0")
        served { service ->
            grant(service, VIEWER, HELD)
            val rows = service.state()
            assertThat(service.pages(2)).containsExactly(
                permitted.subList(0, 2),
                permitted.subList(2, 4),
                permitted.subList(4, 5),
            )
            assertThat(service.pages(1).flatten()).isEqualTo(permitted)
            // a page that ends exactly at the last permitted row carries no cursor
            assertThat(page(service.list(query(limit = "5")))).isEqualTo(permitted to null)
            assertThat(page(service.list(query()))).isEqualTo(permitted to null)
            assertThat(service.state()).describedAs("the read changes nothing").isEqualTo(rows)
        }
    }

    @Test
    fun `INV-AC-28718 a caller permitted for no entity of the window gets the answer of an empty window`() {
        seedAlternating()
        served { service ->
            grant(service, VIEWER, "E-ELSEWHERE")
            val none = service.list(query())
            val empty = service.list(query(submittedBy = principal + "-recorded-nothing"))
            assertThat(none.status).describedAs(none.body).isEqualTo(200)
            assertThat(none.body).isEqualTo("""{"items":[]}""")
            assertThat(none).isEqualTo(empty)
        }
    }

    @Test
    fun `INV-AC-28718 the look-alike key submission view-statuses granted alone gets 200 with no item`() {
        seedAlternating()
        served { service ->
            grant(service, LOOK_ALIKE_HOLDER, HELD, OTHER)
            assertThat(page(service.list(query()))).isEqualTo(emptyList<Item>() to null)
            val replica = service.context.getBean(AuthorityReplica::class.java)
            // the grant is live: the same replica permits the look-alike key
            val control =
                AuthorizationRequest(tenant, Principal("user-a", PrincipalKind.HUMAN), HELD, LOOK_ALIKE)
            assertThat(replica.decide(control, Deadline.after(Duration.ofSeconds(1), Clock.systemUTC())).allowed)
                .isTrue()
        }
    }

    @Test
    fun `INV-AC-28718 an unavailable decision for one of two entities refuses the request with 403 and no item`() {
        seed(HELD, from)
        seed(OTHER, from.plusSeconds(1))
        val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
        val unavailable: Map<String, () -> AuthorityDecision> =
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
        var broken = OTHER
        var answer: () -> AuthorityDecision = { granted }
        val asked = mutableListOf<Triple<String, PrincipalKind, String>>()
        val decision =
            ReplicaDecision { request, _ ->
                asked += Triple(request.principal.id, request.principal.kind, request.action)
                if (request.entityRef == broken) answer() else granted
            }
        served(permission = { replicaPermission(it, decision) }) { service ->
            // control: both decisions available lists both entities
            assertThat(page(service.list(query())).first.map { it.entityRef }).containsExactly(HELD, OTHER)
            listOf(HELD, OTHER).forEach { entity ->
                unavailable.forEach { (label, failing) ->
                    broken = entity
                    answer = failing
                    assertProblem(service.list(query()), 403, "SRT-004", "$label for $entity")
                    assertProblem(service.list(query(limit = "1")), 403, "SRT-004", "$label for $entity, page of 1")
                }
            }
        }
        assertThat(asked.toSet()).containsExactly(Triple("user-a", PrincipalKind.HUMAN, VIEW))
    }

    @Test
    fun `INV-AC-28718 the context's own replica before its first sync gives no decision and the request is 403`() {
        seed(HELD, from)
        served { service -> assertProblem(service.list(query()), 403, "SRT-004", "replica not synced") }
    }

    @Test
    fun `INV-AC-28718 with no replica bound the request is 403 with no item`() {
        seed(HELD, from)
        // the service's own wiring over a factory that holds no AuthorityReplica
        val unbound = { service: ServedService ->
            PermissionConfiguration().entityViewPermission(
                DefaultListableBeanFactory().getBeanProvider(AuthorityReplica::class.java),
                service.context.getBean(TenantContext::class.java),
                DefaultListableBeanFactory().getBeanProvider(Clock::class.java),
                StandardEnvironment(),
            )
        }
        served(permission = unbound) { service ->
            assertProblem(service.list(query()), 403, "SRT-004", "no replica")
        }
    }

    @Test
    fun `INV-AC-28718 a window holding a version without an entityRef is never answered with 200`() {
        seed(HELD, from)
        seed(null, from.plusSeconds(1))
        served { service ->
            grant(service, VIEWER, HELD)
            val outcome = runCatching { service.list(query()) }
            outcome.onSuccess { assertThat(it.status).describedAs(it.body).isGreaterThanOrEqualTo(500) }
            outcome.onFailure { assertThat(it).hasStackTraceContaining("no entity_ref stored") }
        }
    }

    @Test
    fun `INV-AC-28718 a missing parameter, an empty or inverted window and a broken limit are 400 SRT-001`() {
        seed(HELD, from)
        served { service ->
            grant(service, VIEWER, HELD)
            listOf("submittedBy", "recordedFrom", "recordedTo").forEach { missing ->
                assertProblem(
                    service.list(query().filterNot { it.first == missing }),
                    400,
                    "SRT-001",
                    "missing $missing",
                )
            }
            val broken =
                mapOf(
                    "recordedFrom equal to recordedTo" to query(recordedTo = from.toString()),
                    "the same instant in another offset" to query(recordedTo = "2026-05-01T08:00:00+08:00"),
                    "recordedFrom after recordedTo" to
                        query(recordedFrom = to.toString(), recordedTo = from.toString()),
                    "recordedFrom no date-time" to query(recordedFrom = "2026-05-01"),
                    "a five-digit year" to query(recordedTo = "+10000-01-01T00:00:00Z"),
                    "submittedBy too long" to query(submittedBy = "p".repeat(65)),
                    "limit above 500" to query(limit = "501"),
                    "limit 0" to query(limit = "0"),
                    "limit below 0" to query(limit = "-1"),
                    "limit no number" to query(limit = "ten"),
                    "cursor empty" to query(cursor = ""),
                    "cursor not issued" to query(cursor = "v1.abc.def"),
                )
            broken.forEach { (label, sent) -> assertProblem(service.list(sent), 400, "SRT-001", label) }
            assertThat(page(service.list(query(limit = "500"))).first).hasSize(1)
        }
    }

    @Test
    fun `INV-AC-28718 a cursor serves only the filters it was issued for and only unaltered`() {
        val first = seed(HELD, from)
        val second = seed(HELD, from.plusSeconds(1))
        served { service ->
            grant(service, VIEWER, HELD)
            val (items, issued) = page(service.list(query(limit = "1")))
            assertThat(items).containsExactly(first)
            val cursor = checkNotNull(issued)
            assertThat(page(service.list(query(limit = "1", cursor = cursor))).first).containsExactly(second)
            // the same instants written in another offset are the same filter
            val sameWindow = query(cursor = cursor, recordedFrom = "2026-05-01T08:00:00+08:00")
            assertThat(page(service.list(sameWindow)).first).containsExactly(second)
            val (version, payload, tag) = cursor.split('.')
            val altered = if (tag.startsWith("A")) "B" + tag.drop(1) else "A" + tag.drop(1)
            mapOf(
                "other submitter" to query(cursor = cursor, submittedBy = principal + "0"),
                "other recordedFrom" to query(cursor = cursor, recordedFrom = from.minusSeconds(1).toString()),
                "other recordedTo" to query(cursor = cursor, recordedTo = to.plusSeconds(1).toString()),
                "altered tag" to query(cursor = "$version.$payload.$altered"),
                "payload cut" to query(cursor = "$version.${payload.dropLast(4)}.$tag"),
                "another layout" to query(cursor = "v2.$payload.$tag"),
                "a fourth part" to query(cursor = "$cursor.x"),
            ).forEach { (label, sent) -> assertProblem(service.list(sent), 400, "SRT-001", label) }
        }
    }

    @Test
    fun `INV-AC-28718 a cursor serves only the user it was issued to`() {
        val first = seed(HELD, from)
        val second = seed(HELD, from.plusSeconds(1))
        served { service ->
            grant(service, VIEWER, HELD, users = listOf("user-a", "user-b"))
            val issuedToA = checkNotNull(page(service.list(query(limit = "1"))).second)
            val asB = service.list(query(limit = "1", cursor = issuedToA), EndUserDoubles.USER_B)
            assertProblem(asB, 400, "SRT-001", "user-a's cursor sent by user-b")
            // control: user-b is permitted and pages with a cursor of their own
            val (items, issuedToB) = page(service.list(query(limit = "1"), EndUserDoubles.USER_B))
            assertThat(items).containsExactly(first)
            val own = query(limit = "1", cursor = checkNotNull(issuedToB))
            assertThat(page(service.list(own, EndUserDoubles.USER_B))).isEqualTo(listOf(second) to null)
        }
    }

    private fun replicaPermission(
        service: ServedService,
        decision: ReplicaDecision,
    ): EntityViewPermission =
        ReplicaEntityViewPermission(
            decision,
            service.context.getBean(TenantContext::class.java),
            Clock.systemUTC(),
            Duration.ofSeconds(1),
        )

    private companion object {
        const val PATH = "/internal/v1/canonical/ingested-documents"
        const val VIEW = "submission.view-status"
        const val LOOK_ALIKE = "submission.view-statuses"
        const val VIEWER = "fixture-viewer"
        const val LOOK_ALIKE_HOLDER = "fixture-look-alike-holder"
        const val HELD = "E-001"
        const val OTHER = "E-002"
        const val TEN = 10
    }
}
