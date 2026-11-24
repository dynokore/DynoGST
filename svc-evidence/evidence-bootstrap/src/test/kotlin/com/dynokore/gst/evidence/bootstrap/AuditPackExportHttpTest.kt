package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.evidencestore.exportpackage.PackageSource
import com.dynokore.platform.evidencestore.exportpackage.PackageVerification
import com.dynokore.platform.evidencestore.exportpackage.PackageVerifier
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-1971 over HTTP: `createAuditPackExport` and `getAuditPackExport` in the service as TPL-18 composes it, over
 * the real PostgreSQL. The caller is the end user [OPERATOR] of a signed bearer; the permission `audit-pack.export` is
 * held through the replica grant of a role (the test seeds the grant; the code under test reads no role).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions") // one HTTP test class for the two operations of one task
class AuditPackExportHttpTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService
    private val store = GateStoreBeans.store

    @BeforeAll
    fun start() {
        System.setProperty(GateStoreBeans.PROPERTY, "true")
        dir = Files.createTempDirectory("apx")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, GateStoreBeans::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
        System.clearProperty(GateStoreBeans.PROPERTY)
        dir.toFile().deleteRecursively()
    }

    @BeforeEach
    fun reset() {
        store.gate = null
        store.failPuts = false
        store.objects.clear()
    }

    private fun sql(
        text: String,
        vararg args: Any?,
    ): List<List<String?>> =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(text).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                if (st.execute()) {
                    st.resultSet.use { rs ->
                        val n = rs.metaData.columnCount
                        generateSequence { if (rs.next()) (1..n).map { rs.getString(it) } else null }.toList()
                    }
                } else {
                    emptyList()
                }
            }
        }

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The tenant of one test with both entities registered and, when [grant], [OPERATOR] holding the permission. */
    private fun tenant(grant: Boolean = true): UUID {
        val tenant = UUID.randomUUID()
        val node =
            StructureNode(
                StructureNodeKind.ENTITY,
                null,
                retired = false,
                name = "entity",
                code = null,
                profile = emptyList(),
                structureVersion = 1,
            )
        listOf(ENTITY, OTHER_ENTITY).forEach {
            service
                .bean(StructureReplica::class.java)
                .accept(OrganisationStructureChanged(TenantId(tenant), it, StructureChange.CREATED, node))
        }
        if (grant) {
            service.grants.grant(
                TenantId(tenant),
                OPERATOR,
                service.grants.role(GRANTED_ROLE, GrantScope.TENANT, tenant.toString()),
            )
        }
        return tenant
    }

    /** One item of each of the six classes of [period] of [entity]; the work-paper object goes to the store. */
    private fun seed(
        tenant: UUID,
        period: String,
        seq: Long,
    ) {
        EvidenceDb.superuser().use { c ->
            RetentionPartitionFixture.ensureRetentionPartitions(c, tenant, ENTITY, period)
        }
        RetentionPartitionFixture.CHAIN_TABLES.forEach {
            sql(
                "insert into evidence.$it (id, tenant_id, entity_ref, period_ref, seq, evidence_type, content, " +
                    "content_hash, previous_hash, recorded_at) values (gen_random_uuid(), ?, ?, ?, ?, 'X', ?, ?, " +
                    "null, now())",
                tenant,
                ENTITY,
                period,
                seq,
                "c-$it-$period".toByteArray(),
                "sha256:" + "a".repeat(HASH_LENGTH),
            )
        }
        seedLocatorAndWorkPaper(tenant, period)
    }

    private fun seedLocatorAndWorkPaper(
        tenant: UUID,
        period: String,
    ) {
        sql(
            "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                "transaction_date, trace_reference, source_event_id, recorded_at, source_document_ref) " +
                "values (?, ?, 1, ?, ?, date '2026-01-15', 'trace', ?, now(), ?)",
            tenant,
            UUID.randomUUID(),
            ENTITY,
            period,
            UUID.randomUUID().toString(),
            "dms://$tenant/$ENTITY/$period",
        )
        val reference = "reconciliation/$tenant/run-$period/v1"
        val bytes = """{"header":{"periodRef":"$period"}}""".toByteArray()
        store.objects[reference] = bytes
        sql(
            "insert into evidence.evidence_artefact (id, tenant_id, entity_ref, artefact_type, reference, hash, " +
                "event_id, recorded_at, period_ref) values (?, ?, ?, 'RECONCILIATION_WORK_PAPER', ?, ?, ?, now(), ?)",
            UUID.randomUUID(),
            tenant,
            ENTITY,
            reference,
            sha(bytes),
            UUID.randomUUID().toString(),
            period,
        )
    }

    private val port: String
        get() = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")!!

    private fun call(
        method: String,
        tenant: UUID,
        entity: String,
        period: String,
        suffix: String,
    ): Pair<HttpResponse<String>, JsonNode> {
        val path = "/internal/v1/evidence/entities/$entity/periods/$period/audit-packs$suffix"
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer ${TestIdentity.token(OPERATOR, tenant)}")
                .header("X-Workload-Token", "token-console-bff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response to mapper.readTree(response.body())
    }

    private fun create(
        tenant: UUID,
        period: String = PERIOD,
    ) = call("POST", tenant, ENTITY, period, "")

    private fun read(
        tenant: UUID,
        packId: String,
        period: String = PERIOD,
        entity: String = ENTITY,
    ) = call("GET", tenant, entity, period, "/$packId")

    private fun packFiles(
        tenant: UUID,
        packId: String,
    ): Map<String, ByteArray> {
        val prefix = "exports/offboarding/$tenant/$packId/"
        return store.objects.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    private fun verify(
        tenant: UUID,
        packId: String,
    ): PackageVerification {
        val files = packFiles(tenant, packId)
        return PackageVerifier.verify(
            object : PackageSource {
                override fun paths(): Set<String> = files.keys

                override fun read(path: String): ByteArray = files.getValue(path)
            },
        )
    }

    private fun stored(tenant: UUID): Int =
        store.objects.keys.count {
            it.startsWith("exports/offboarding/$tenant/") && it.endsWith("/manifest-sha256.txt")
        }

    private fun jobs(tenant: UUID): Int =
        sql("select count(*) from evidence.audit_pack_job where tenant_id = ?", tenant).single().single()!!.toInt()

    private fun Pair<HttpResponse<String>, JsonNode>.status() = first.statusCode()

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the task fixes this test name
    fun `INV-AC-1971 the audit pack of an entity and period opens with the library verifier and holds all six classes`() {
        val tenant = tenant()
        seed(tenant, PERIOD, 1)
        seed(tenant, OTHER_PERIOD, 2)
        val created = create(tenant)
        created.status() shouldBe 202
        val packId = created.second.get("packId").asString()
        val answer = read(tenant, packId)
        answer.status() shouldBe 200
        answer.second.get("status").asString() shouldBe "READY"
        val contents = answer.second.get("contents")
        (0 until contents.size()).map { contents.get(it).asString() } shouldBe CONTENTS
        answer.second.get("integrityStatus").asString() shouldBe "NEVER_RUN"
        answer.second.get("expectedRetrievalSeconds").asInt() shouldBe 0
        val verified = verify(tenant, packId) as PackageVerification.Valid
        verified.itemCounts shouldBe
            mapOf(
                "transmitted-xml" to 1,
                "acknowledgements" to 1,
                "decision-traces" to 1,
                "reconciliation-work-papers" to 1,
                "source-document-references" to 1,
                "audit-log" to 1,
            )
        val files = packFiles(tenant, packId)
        (VERIFICATION_REPORT in files) shouldBe true
        String(files.getValue("tagmanifest-sha256.txt")).lines().any { it.endsWith(" $VERIFICATION_REPORT") } shouldBe
            true
        files.values.none { String(it).contains(OTHER_PERIOD) } shouldBe true
    }

    @Test
    fun `INV-AC-1971 a repeat create returns the same pack id and stores one package`() {
        val tenant = tenant()
        seed(tenant, PERIOD, 1)
        val first = create(tenant)
        val second = create(tenant)
        second.status() shouldBe 202
        second.second.get("packId").asString() shouldBe first.second.get("packId").asString()
        second.second.get("status").asString() shouldBe "READY"
        stored(tenant) shouldBe 1
        jobs(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-1971 a denied decision answers 403 and registers no job`() {
        val tenant = tenant(grant = false)
        seed(tenant, PERIOD, 1)
        val denied = create(tenant)
        denied.status() shouldBe 403
        denied.second.get("code").asString() shouldBe "SRT-004"
        jobs(tenant) shouldBe 0
        stored(tenant) shouldBe 0
        read(tenant, UUID.randomUUID().toString()).status() shouldBe 403
    }

    @Test
    fun `INV-AC-1971 a period without items is READY with six zero counts`() {
        val tenant = tenant()
        val created = create(tenant, period = EMPTY_PERIOD)
        created.status() shouldBe 202
        val packId = created.second.get("packId").asString()
        read(tenant, packId, period = EMPTY_PERIOD).second.get("status").asString() shouldBe "READY"
        val verified = verify(tenant, packId) as PackageVerification.Valid
        verified.itemCounts.size shouldBe SIX
        verified.itemCounts.values.toList() shouldBe List(SIX) { 0 }
    }

    @Test
    fun `INV-AC-1971 a pack id of another entity, period or tenant answers 404`() {
        val tenant = tenant()
        val other = tenant()
        seed(tenant, PERIOD, 1)
        val packId = create(tenant).second.get("packId").asString()
        read(tenant, packId).status() shouldBe 200
        read(tenant, packId, period = OTHER_PERIOD).status() shouldBe 404
        read(tenant, packId, entity = OTHER_ENTITY).status() shouldBe 404
        read(tenant, UUID.randomUUID().toString()).status() shouldBe 404
        read(other, packId).status() shouldBe 404
    }

    @Test
    fun `INV-AC-1971 the audit-log row of each read names the acting user ID and not a role`() {
        val tenant = tenant()
        seed(tenant, PERIOD, 1)
        val packId = create(tenant).second.get("packId").asString()
        read(tenant, packId).status() shouldBe 200
        val audits =
            sql(
                "select data->>'actor', data::text from evidence.outbox where tenant_id = ? " +
                    "and type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' order by seq",
                tenant,
            )
        audits.map { it[0] } shouldBe listOf(OPERATOR, OPERATOR)
        audits.forEach { it[1]!! shouldNotContain GRANTED_ROLE }
    }

    private companion object {
        const val ENTITY = "ENT-A"
        const val OTHER_ENTITY = "ENT-B"
        const val PERIOD = "2026-Q1"
        const val OTHER_PERIOD = "2026-Q2"
        const val EMPTY_PERIOD = "2026-Q3"
        const val OPERATOR = "u-operator"
        const val GRANTED_ROLE = "tax.operator"
        const val VERIFICATION_REPORT = "verification-report.txt"
        const val HASH_LENGTH = 64
        const val SIX = 6
        val CONTENTS =
            listOf(
                "SUBMITTED_XML",
                "ACKNOWLEDGEMENTS",
                "DECISION_TRACES",
                "RECONCILIATION_WORK_PAPER",
                "SOURCE_REFERENCES",
                "AUDIT_LOG",
                "VERIFICATION_REPORT",
            )
    }
}
