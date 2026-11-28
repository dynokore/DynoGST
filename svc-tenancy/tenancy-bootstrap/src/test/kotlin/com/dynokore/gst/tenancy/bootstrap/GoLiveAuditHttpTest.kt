package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.lifecycle.RenditionCoverage
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/**
 * The audit entry of goLive over HTTP through the filter chain of service-runtime, over PostgreSQL (INV-REQ-963,
 * INV-REQ-1226, NIST SP 800-53 AU-2 and AU-3). The audit entries and the transaction ids are read through the owner
 * connection and compared by equality. The caller is [ADMIN] and the second confirmer [APPROVER].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoLiveAuditHttpTest {
    /**
     * The trigger and rendition coverage that leave the gate to the stored facts (their producers come later). No
     * stereotype: the component scan never picks it up, only the context that lists it.
     */
    class CoveredPorts {
        @Bean
        @Primary
        fun coveredTriggers(): TriggerCoverage = TriggerCoverage { emptyList() }

        @Bean
        @Primary
        fun coveredRendition(): RenditionCoverage = RenditionCoverage { emptyList() }
    }

    private class HttpAnswer(
        val status: Int,
        val body: String,
    ) {
        val code: String? get() = Regex(""""code"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    }

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private lateinit var dir: Path
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var lab: Lab

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("inv-t-29443-")
        context =
            BoundaryFixture.startServlet(
                dir,
                listOf(EntityFactsIdentity::class.java, CoveredPorts::class.java),
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                    "management.server.port=0",
                ),
                javaClass.classLoader,
            )
        val env = ProfileEnv(context)
        lab = Lab(env, CertificationEnv(env, TopicLog()), ElectionEnv(env), Ports(), Clock.systemUTC())
    }

    @AfterAll
    fun stop() {
        context.close()
        dir.toFile().deleteRecursively()
    }

    /** A DRAFT entity whose gate is met and one whose gate is not (no certification and no election). */
    private fun scenario(block: (EntityResponse, EntityResponse) -> Unit) =
        lab.env.withGroup(2) { tenant: TenantId, _, entities ->
            val (met, unmet) = entities
            lifecycleReady(lab, met)
            lab.elections.usualPeople(unmet)
            lab.people.caller = ADMIN
            EntityFactsIdentity.principal =
                EndUserPrincipal("user-a", tenant, entities.map { EntityRef(it.entityRef) }.toSet())
            assertThat(listOf(storedState(met), storedState(unmet))).containsExactly("DRAFT", "DRAFT")
            block(met, unmet)
        }

    private fun goLive(
        entity: EntityResponse,
        key: UUID = UUID.randomUUID(),
        confirmer: String = APPROVER,
    ): HttpAnswer {
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/tenancy/entities/${entity.entityRef}/go-live"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .header("X-Flow-Context", FIXED_FLOW_CONTEXT)
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}")
                .POST(HttpRequest.BodyPublishers.ofString("""{"secondConfirmationBy":"$confirmer"}"""))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { HttpAnswer(it.statusCode(), it.body()) }
    }

    /** The transaction ids (`xmin`) of the outbox rows of [entity] that match [condition], in write order. */
    private fun transactionIds(
        entity: EntityResponse,
        condition: String,
        value: String,
    ): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select xmin::text from tenancy.outbox where entity_ref = ? and $condition = ? order by seq",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.setString(2, value)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    private fun auditTransactionIds(entity: EntityResponse) = transactionIds(entity, "topic", AUDIT_TOPIC)

    private fun profileTransactionIds(entity: EntityResponse) = transactionIds(entity, "type", PROFILE_CHANGED)

    private fun field(
        row: JsonNode,
        name: String,
    ): String? = row.get(name)?.asString()

    @Test
    fun `INV-AC-29440 goLive records one entity went-live entry with the caller and the second confirmer`() =
        scenario { entity, _ ->
            val before = auditRows(entity, "")

            val answer = goLive(entity)

            assertThat(answer.status).describedAs(answer.body).isEqualTo(200)
            assertThat(storedState(entity)).isEqualTo("LIVE")
            val after = auditRows(entity, "")
            assertThat(after.size).isEqualTo(before.size + 1)
            assertThat(after.dropLast(1)).isEqualTo(before)
            val entry = after.last()
            assertThat(field(entry, "action")).describedAs(entry.toString()).isEqualTo("entity.went-live")
            assertThat(field(entry, "actor")).isEqualTo(ADMIN)
            assertThat(field(entry, "scope")).isEqualTo(entity.entityRef)
            assertThat(field(entry, "reason")).isEqualTo("go-live confirmed by $APPROVER")
        }

    @Test
    fun `INV-AC-29440 the went-live entry and the profile change of the transition commit in one transaction`() =
        scenario { entity, _ ->
            val auditsBefore = auditTransactionIds(entity).size
            val changesBefore = profileTransactionIds(entity).size

            assertThat(goLive(entity).status).isEqualTo(200)

            val audits = auditTransactionIds(entity).drop(auditsBefore)
            val changes = profileTransactionIds(entity).drop(changesBefore)
            assertThat(audits).hasSize(1)
            assertThat(changes).hasSize(1)
            assertThat(audits.single()).isEqualTo(changes.single())
        }

    @Test
    fun `INV-AC-29440 a repeat with the same or a new Idempotency-Key adds no audit entry`() =
        scenario { entity, _ ->
            val key = UUID.randomUUID()
            assertThat(goLive(entity, key).status).isEqualTo(200)
            val recorded = auditRows(entity, "")

            listOf(key, UUID.randomUUID()).forEach { repeatKey ->
                val repeat = goLive(entity, repeatKey)
                assertThat(repeat.status).describedAs(repeat.body).isEqualTo(200)
                assertThat(auditRows(entity, "").size).isEqualTo(recorded.size)
            }
            assertThat(auditRows(entity, "")).isEqualTo(recorded)
        }

    @Test
    fun `INV-AC-29440 goLive confirmed by the caller is refused and adds no audit entry`() =
        scenario { entity, _ ->
            val before = auditRows(entity, "")

            val answer = goLive(entity, confirmer = ADMIN)

            assertThat(listOf(answer.status, answer.code)).describedAs(answer.body).isEqualTo(listOf(403, "SRT-004"))
            assertThat(storedState(entity)).isEqualTo("DRAFT")
            assertThat(auditRows(entity, "")).isEqualTo(before)
        }

    @Test
    fun `INV-AC-29440 goLive of an entity whose gate is not met is refused and adds no audit entry`() =
        scenario { _, unmet ->
            val before = auditRows(unmet, "")

            val answer = goLive(unmet)

            assertThat(listOf(answer.status, answer.code)).describedAs(answer.body).isEqualTo(listOf(422, "POL-014"))
            assertThat(storedState(unmet)).isEqualTo("DRAFT")
            assertThat(auditRows(unmet, "")).isEqualTo(before)
        }

    private companion object {
        const val AUDIT_TOPIC = "gst.platform.audit-entry-recorded.v1"
    }
}
