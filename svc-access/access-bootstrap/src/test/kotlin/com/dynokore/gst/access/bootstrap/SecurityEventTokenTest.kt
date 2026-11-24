package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.IssueAccessTokenResponse.Decision
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-T-24875: every outcome of `issueAccessToken` is one audit entry (action `issueAccessToken`, actor the
 * principal's ID, detail `TOKEN_ISSUED` or `REFUSED:<reasonCode>`) and one security event of class credential_use that
 * never carries the credential (INV-REQ-1384; owner ruling 2026-11-18, option A).
 */
class SecurityEventTokenTest {
    private val mapper = JsonMapper.builder().build()

    private class Collector : AutoCloseable {
        val received = CopyOnWriteArrayList<String>()
        private val pool = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = pool
                createContext("/") { exchange ->
                    received += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

        fun await(count: Int): List<String> {
            val end = System.nanoTime() + 5_000_000_000L
            while (received.size < count && System.nanoTime() < end) Thread.sleep(10)
            Thread.sleep(300)
            return received.toList()
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private class Subject(
        val ref: String,
        val proof: String,
    )

    private fun run(block: (TokenKit, Collector) -> Unit) {
        BoundaryFixture.deleteVerificationKeys()
        val mounted = BoundaryFixture.mount(TestSecrets.newSigningKey())
        Collector().use { collector ->
            BoundaryFixture
                .runner(mounted = mounted)
                .withPropertyValues(
                    "gst.bindings.telemetry-export=bundled-otel",
                    "gst.bindings.telemetry-export-otlp-endpoint=${collector.endpoint}",
                ).run { context ->
                    context.startupFailure?.let { throw it }
                    val tokens = TokenKit(PrincipalKit(context), mounted, context.getBean(SignerCalls::class.java))
                    try {
                        block(tokens, collector)
                    } finally {
                        tokens.close()
                    }
                }
        }
    }

    private fun TokenKit.principal(): Subject {
        kit.entity("ENT-1")
        val key = kit.newKey("client-key-" + UUID.randomUUID())
        val created = kit.create("INGEST", scope = "entity:ENT-1", method = PrincipalKit.JWT, key = key)
        return Subject(created.principalRef, kit.proof(key, created.principalRef))
    }

    /** The stored audit rows of `issueAccessToken` whose actor is [ref], as stored (any tenant, superuser). */
    private fun audits(ref: String): List<JsonNode> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "SELECT data::text FROM access.outbox WHERE " +
                        "type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' " +
                        "AND data->>'action' = 'issueAccessToken' AND data->>'actor' = ?",
                ).use { statement ->
                    statement.setString(1, ref)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(mapper.readTree(rows.getString(1))) }
                    }
                }
        }

    private fun attributes(body: String): Map<String, String> =
        mapper
            .readTree(body)
            .at("/resourceLogs/0/scopeLogs/0/logRecords/0")
            .get("attributes")
            .associate { it.get("key").asString() to it.at("/value/stringValue").asString() }

    private fun assertOneEntry(
        ref: String,
        detail: String,
        proof: String,
    ) {
        val audits = audits(ref)
        assertThat(audits).hasSize(1)
        val entry = audits.single()
        assertThat(entry.get("actor").asString()).isEqualTo(ref)
        assertThat(entry.get("action").asString()).isEqualTo("issueAccessToken")
        assertThat(entry.toString()).contains("\"$detail\"").doesNotContain(proof).doesNotContain("PRIVATE_KEY_JWT")
    }

    private fun assertRecord(
        body: String,
        ref: String,
        t: TokenKit,
    ) = assertThat(attributes(body))
        .isEqualTo(
            mapOf(
                "gst.security.class" to "credential_use",
                "user.id" to ref,
                "gst.tenant.id" to t.kit.tenant.toString(),
            ),
        )

    @Test
    fun `INV-AC-24874 an issued token writes one TOKEN_ISSUED entry and yields one credential_use record`() =
        run { t, collector ->
            val subject = t.principal()
            val issued = t.issue(subject.ref, subject.proof)
            assertThat(issued.decision).isEqualTo(Decision.ISSUED)
            assertOneEntry(subject.ref, "TOKEN_ISSUED", subject.proof)
            assertThat(audits(subject.ref).single().toString()).doesNotContain(issued.accessToken!!)
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertRecord(records.single(), subject.ref, t)
            assertThat(records.single()).doesNotContain(subject.proof).doesNotContain(issued.accessToken!!)
            assertThat(t.logLines().joinToString("\n")).doesNotContain(subject.proof).doesNotContain(issued.accessToken!!)
        }

    @Test
    fun `INV-AC-24874 a refused revoked principal writes one REFUSED entry and yields one credential_use record`() =
        run { t, collector ->
            val subject = t.principal()
            t.kit.revoke(subject.ref)
            assertThat(collector.await(0)).isEmpty()
            val refused = t.issue(subject.ref, subject.proof)
            assertThat(refused.decision).isEqualTo(Decision.REFUSED)
            assertOneEntry(subject.ref, "REFUSED:" + refused.reasonCode, subject.proof)
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertRecord(records.single(), subject.ref, t)
            assertThat(records.single()).doesNotContain(subject.proof)
            assertThat(t.logLines().joinToString("\n")).doesNotContain(subject.proof)
        }

    @Test
    fun `INV-AC-24874 a refused unknown principal writes one entry under the platform binding`() =
        run { t, _ ->
            val subject = t.principal()
            val unknown = "PRN-UNKNOWN-" + UUID.randomUUID()
            val refused = t.issue(unknown, subject.proof)
            assertThat(refused.decision).isEqualTo(Decision.REFUSED)
            assertOneEntry(unknown, "REFUSED:" + refused.reasonCode, subject.proof)
        }

    @Test
    fun `INV-AC-24874 a call to getVerificationKeys yields no credential_use record and no entry`() =
        run { t, collector ->
            val subject = t.principal()
            assertThat(t.published()).isNotNull
            assertThat(collector.await(1)).isEmpty()
            assertThat(audits(subject.ref)).isEmpty()
        }
}
