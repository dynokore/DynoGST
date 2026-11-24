package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.application.artefact.RENDITION_EVIDENCE_VIEW
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * The role map of [ListRenditionSampleChecksHttpTest]: one role holds the key. Active only while the test sets
 * [PROPERTY]: the scan of other tests skips it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(RenditionRoleMapBeans.PROPERTY, havingValue = "true")
class RenditionRoleMapBeans {
    @Bean
    @Primary
    fun renditionRoleMap(): OperationRoleMap =
        OperationRoleMap {
            if (it == RENDITION_EVIDENCE_VIEW) mapOf("rendition.viewer" to CellAllowance.PERMITTED) else emptyMap()
        }

    companion object {
        const val PROPERTY = "evidence.test.rendition-role-map"
    }
}

/**
 * INV-AC-27604 over HTTP: `listRenditionSampleChecks` in the service as TPL-18 composes it, over the real PostgreSQL,
 * with the production `VerificationController`, `ListRenditionSampleChecks` bean and `SampleCheckCursor` over a
 * mounted key. The permission of each principal is the TENANT-scope grant fed to the service's one replica.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListRenditionSampleChecksHttpTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService

    @BeforeAll
    fun start() {
        System.setProperty(RenditionRoleMapBeans.PROPERTY, "true")
        dir = Files.createTempDirectory("rsc")
        Files.writeString(dir.resolve("evidence.sample-check-cursor-key"), "k".repeat(KEY_LENGTH))
        service =
            RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, RenditionRoleMapBeans::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
        System.clearProperty(RenditionRoleMapBeans.PROPERTY)
        dir.toFile().deleteRecursively()
    }

    private val port: String
        get() = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")!!

    private class Reply(
        val status: Int,
        val body: JsonNode,
    ) {
        val code: String? get() = body.get("code")?.asString()
    }

    private fun get(
        entity: String,
        tenant: UUID,
        user: String? = HOLDER,
        query: String = "",
    ): Reply {
        val uri = "http://localhost:$port/internal/v1/evidence/entities/$entity/rendition-sample-checks$query"
        val builder = HttpRequest.newBuilder(URI.create(uri)).header("X-Workload-Token", "token-console-bff")
        user?.let { builder.header("Authorization", "Bearer ${TestIdentity.token(it, tenant)}") }
        val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), mapper.readTree(response.body()))
    }

    /** A tenant whose [HOLDER] holds the key through a role and whose [OTHER] holds a role without it. */
    private fun tenant(): UUID {
        val tenant = UUID.randomUUID()
        service.grants.grant(
            TenantId(tenant),
            HOLDER,
            service.grants.role("rendition.viewer", GrantScope.TENANT, tenant.toString()),
        )
        service.grants.grant(
            TenantId(tenant),
            OTHER,
            service.grants.role("unrelated.role", GrantScope.TENANT, tenant.toString()),
        )
        return tenant
    }

    @Suppress("LongParameterList") // one stored artefact row
    private fun store(
        tenant: UUID,
        entity: String,
        type: String,
        reference: String,
        recordedAt: Instant,
        hash: Char,
    ) = EvidenceDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into evidence.evidence_artefact " +
                    "(id, tenant_id, entity_ref, artefact_type, reference, hash, event_id, recorded_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setObject(1, UUID.randomUUID())
                st.setObject(2, tenant)
                st.setString(3, entity)
                st.setString(4, type)
                st.setString(5, reference)
                st.setString(6, hash.toString().repeat(HASH_LENGTH))
                st.setString(7, UUID.randomUUID().toString())
                st.setTimestamp(8, Timestamp.from(recordedAt))
                st.executeUpdate()
            }
    }

    private fun seeded(): UUID {
        val t = tenant()
        store(t, "E1", "SAMPLE_CHECK", "s-1", Instant.parse("2026-11-01T00:00:00Z"), 'a')
        store(t, "E1", "SAMPLE_CHECK", "s-2", Instant.parse("2026-11-02T00:00:00.123456Z"), 'b')
        store(t, "E1", "SAMPLE_CHECK", "s-3", Instant.parse("2026-11-03T00:00:00Z"), 'c')
        store(t, "E1", "RENDITION", "r-1", Instant.parse("2026-11-04T00:00:00Z"), 'd')
        store(t, "E2", "SAMPLE_CHECK", "other-entity", Instant.parse("2026-11-05T00:00:00Z"), 'e')
        return t
    }

    @Test
    fun `INV-AC-27604 a holder reads the page of the entity with recordedAt from the stored column`() {
        val t = seeded()
        val answer = get("E1", t, query = "?limit=2")
        answer.status shouldBe 200
        val items = answer.body.get("items")
        items.size() shouldBe 2
        items.get(0).get("reference").asString() shouldBe "s-1"
        items.get(0).get("artefactType").asString() shouldBe "SAMPLE_CHECK"
        items.get(0).get("hash").asString() shouldBe "a".repeat(HASH_LENGTH)
        OffsetDateTime.parse(items.get(0).get("recordedAt").asString()).toInstant() shouldBe
            Instant.parse("2026-11-01T00:00:00Z")
        OffsetDateTime.parse(items.get(1).get("recordedAt").asString()).toInstant() shouldBe
            Instant.parse("2026-11-02T00:00:00.123456Z")
        answer.body
            .get("nextCursor")
            .asString()
            .isNotBlank() shouldBe true
    }

    @Test
    fun `INV-AC-27604 the second page has no nextCursor and holds the rest`() {
        val t = seeded()
        val first = get("E1", t, query = "?limit=2")
        val cursor = first.body.get("nextCursor").asString()
        val second = get("E1", t, query = "?limit=2&cursor=$cursor")
        second.status shouldBe 200
        second.body.get("items").size() shouldBe 1
        second.body
            .get("items")
            .get(0)
            .get("reference")
            .asString() shouldBe "s-3"
        second.body.has("nextCursor") shouldBe false
    }

    @Test
    fun `INV-AC-27604 a principal without the key gets 403 SRT-004 and no artefact is read`() {
        val t = seeded()
        val answer = get("E1", t, user = OTHER)
        answer.status shouldBe 403
        answer.code shouldBe "SRT-004"
        answer.body.has("items") shouldBe false
    }

    @Test
    fun `INV-AC-27604 limit 0 and limit 501 answer 400 SRT-001`() {
        val t = seeded()
        listOf("0", "501").forEach {
            val answer = get("E1", t, query = "?limit=$it")
            answer.status shouldBe 400
            answer.code shouldBe "SRT-001"
        }
    }

    @Test
    fun `INV-AC-27604 a tampered cursor answers 400 SRT-001`() {
        val t = seeded()
        val cursor = get("E1", t, query = "?limit=1").body.get("nextCursor").asString()
        val tampered = cursor.dropLast(1) + if (cursor.last() == 'A') 'B' else 'A'
        val answer = get("E1", t, query = "?limit=1&cursor=$tampered")
        answer.status shouldBe 400
        answer.code shouldBe "SRT-001"
    }

    @Test
    fun `INV-AC-27604 a cursor of getAcknowledgementChain answers 400 SRT-001`() {
        val t = seeded()
        val chain =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("ACK-1\u0000${Instant.now()}\u0000ACKNOWLEDGEMENT\u00001".toByteArray())
        val answer = get("E1", t, query = "?limit=1&cursor=$chain")
        answer.status shouldBe 400
        answer.code shouldBe "SRT-001"
    }

    @Test
    fun `INV-AC-27604 a request without an end user answers 401`() {
        val t = seeded()
        get("E1", t, user = null).status shouldBe 401
    }

    private companion object {
        const val HOLDER = "user-holder"
        const val OTHER = "user-other"
        const val KEY_LENGTH = 32
        const val HASH_LENGTH = 64
    }
}
