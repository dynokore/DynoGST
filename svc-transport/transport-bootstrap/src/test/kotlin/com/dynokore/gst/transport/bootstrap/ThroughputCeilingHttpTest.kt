package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.adapters.http.RouteCaller
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

private const val CEILING_BFF_SUBJECT = "system:serviceaccount:platform:console-bff"
private const val CEILING_OTHER_SUBJECT = "system:serviceaccount:platform:tenancy-svc"

/**
 * `setThroughputCeiling` over real HTTP and a real database (INV-T-24959; INV-REQ-1317; INV-AC-24957): the console
 * command stores a ceiling at or below the agreed rate of the connection and refuses a higher one, a caller without
 * `vp-56.set`, and a connection of another tenant. The AuthorityReplica is the real one, fed per test.
 */
class ThroughputCeilingHttpTest {
    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    private class Running(
        val context: ConfigurableApplicationContext,
        val harness: Harness,
        val port: Int,
    ) {
        private val authority: AuthorityReplica = context.getBean(AuthorityReplica::class.java)
        private var sequence = 0L

        fun user(
            tenant: UUID,
            vararg keys: String,
        ): String {
            val header = TestPrincipals.register(tenant, setOf("E1"))
            if (keys.isNotEmpty()) {
                val since = Clock.systemUTC().instant().minusSeconds(60)
                val grants = keys.map { Grant(it, ScopeLevel.TENANT, "x", since) }
                val record = AuthorityRecord.GrantRecord("tenant-wide", grants)
                authority.accept(AuthorityChanged(TenantId(tenant), TestPrincipals.actorOf(header), ++sequence, record))
            }
            return header
        }

        fun ready() = authority.onSnapshotComplete(0)
    }

    private fun run(
        dir: Path,
        block: (Running) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val secondary = AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
        val properties =
            TransportContext.propertyValues(dir, TransportDb.jdbcUrl) +
                listOf(
                    "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}",
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                    "transport.ap.adapters[1].id=${secondary.value}",
                    "transport.ap.adapters[1].path=secondary",
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=$CEILING_BFF_SUBJECT",
                    "service-runtime.workload-identity.callers[1].service=tenancy-svc",
                    "service-runtime.workload-identity.callers[1].subject=$CEILING_OTHER_SUBJECT",
                )
        val context =
            SpringApplicationBuilder(TransportApplication::class.java, CallerTestConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(*properties.toTypedArray())
                .run("--server.port=0", "--management.server.port=0")
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            val running = Running(context, Harness(context, primary, secondary), port)
            running.ready()
            block(running)
        } finally {
            context.close()
        }
    }

    private class Answer(
        val status: Int,
        val body: String,
    )

    private fun Running.put(
        connection: String,
        body: String,
        authorization: String?,
        token: String = "W-bff",
        key: String = UUID.randomUUID().toString(),
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$CONNECTIONS/$connection/throughput-ceiling"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header(WorkloadToken.HEADER_NAME, token)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
        authorization?.let { request.header("Authorization", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Running.set(
        connection: String,
        ceiling: String,
        authorization: String?,
        token: String = "W-bff",
        key: String = UUID.randomUUID().toString(),
    ) = put(connection, """{"throughputCeiling":$ceiling}""", authorization, token, key)

    /** C1 of the criterion: the agreed rate [agreed] and a stored ceiling of 90. */
    private fun Running.connection(
        tenant: UUID,
        agreed: String = "100",
        stored: String = "90",
    ): String {
        harness.connect(tenant, harness.primary, agreed)
        harness.asTenant(tenant) {
            harness
                .dsl
                .update(AP_CONNECTION)
                .set(AP_CONNECTION.TENANT_CEILING, BigDecimal(stored))
                .where(AP_CONNECTION.TENANT_ID.eq(tenant))
                .execute()
        }
        return harness.primary.value
    }

    private fun Running.stored(
        tenant: UUID,
        connection: String,
    ): BigDecimal? =
        harness.asTenant(tenant) {
            harness
                .dsl
                .select(AP_CONNECTION.TENANT_CEILING)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(tenant))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connection))
                .fetchOne(AP_CONNECTION.TENANT_CEILING)
        }

    private fun Running.rows(
        tenant: UUID,
        connection: String,
    ): Int =
        harness.asTenant(tenant) {
            harness.dsl.fetchCount(
                AP_CONNECTION,
                AP_CONNECTION.TENANT_ID.eq(tenant).and(AP_CONNECTION.ADAPTER_ID.eq(connection)),
            )
        }

    private fun Running.permitted(tenant: UUID) = user(tenant, RouteCaller.THROUGHPUT_CEILING_SET_PERMISSION)

    private fun BigDecimal?.shouldBeNumber(expected: String) {
        (this != null && this.compareTo(BigDecimal(expected)) == 0) shouldBe true
    }

    @Test
    fun `INV-AC-24957 (1) a ceiling of 80 under the agreed rate 100 answers 200 and one row holds 80`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val answer = r.set(connection, "80", r.permitted(tenant))
        answer.status shouldBe 200
        val json = mapper.readTree(answer.body)
        BigDecimal(json.get("throughputCeiling").asString()).compareTo(BigDecimal("80")) shouldBe 0
        BigDecimal(json.get("agreedRate").asString()).compareTo(BigDecimal("100")) shouldBe 0
        json.get("connectionRef").asString() shouldBe connection
        r.rows(tenant, connection) shouldBe 1
        r.stored(tenant, connection).shouldBeNumber("80")
    }

    @Test
    fun `INV-AC-24957 (2) a ceiling equal to the agreed rate 100 is accepted and 100 is stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val answer = r.set(connection, "100", r.permitted(tenant))
        answer.status shouldBe 200
        BigDecimal(mapper.readTree(answer.body).get("throughputCeiling").asString()).compareTo(BigDecimal("100")) shouldBe 0
        r.stored(tenant, connection).shouldBeNumber("100")
    }

    @Test
    fun `INV-AC-24957 (3) a ceiling of 101 and one of 100_0001 above the agreed rate are refused POL-008, nothing stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val user = r.permitted(tenant)
        listOf("101", "100.0001").forEach {
            val answer = r.set(connection, it, user)
            answer.status shouldBe 422
            answer.body shouldContain "POL-008"
            r.stored(tenant, connection).shouldBeNumber("90")
        }
    }

    @Test
    fun `INV-AC-24957 a ref that names no connection of the tenant answers 404 and nothing is stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val answer = r.set("nope", "50", r.permitted(tenant))
        answer.status shouldBe 404
        answer.body shouldNotContain "POL-008"
        r.stored(tenant, connection).shouldBeNumber("90")
    }

    @Test
    fun `INV-AC-24957 (5) a caller without the scoped permission key is refused 403 and nothing is stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        r.set(connection, "80", r.user(tenant)).status shouldBe 403
        r.set(connection, "80", r.user(tenant, RouteCaller.ADAPTER_CONFIGURE_PERMISSION)).status shouldBe 403
        r.set(connection, "80", null).status shouldBe 401
        r.stored(tenant, connection).shouldBeNumber("90")
    }

    @Test
    fun `INV-AC-24957 (6) a ceiling of 0 or below is refused by validation SRT-001 and nothing is stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val user = r.permitted(tenant)
        listOf("0", "-1").forEach {
            val answer = r.set(connection, it, user)
            answer.status shouldBe 400
            answer.body shouldContain "SRT-001"
        }
        r.stored(tenant, connection).shouldBeNumber("90")
    }

    @Test
    fun `INV-AC-24957 another tenant's connection answers 404 and its row is untouched`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val connection = r.connection(owner)
        val answer = r.set(connection, "50", r.permitted(other))
        answer.status shouldBe 404
        r.stored(owner, connection).shouldBeNumber("90")
    }

    @Test
    fun `INV-AC-24957 a caller outside console-bff is refused 403 by the generated filter and nothing is stored`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val answer = r.set(connection, "80", r.permitted(tenant), token = "W-tenancy")
        answer.status shouldBe 403
        r.stored(tenant, connection).shouldBeNumber("90")
    }

    @Test
    fun `INV-AC-24957 the same Idempotency-Key returns the original outcome and a lower repeat stores once`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val tenant = UUID.randomUUID()
        val connection = r.connection(tenant)
        val user = r.permitted(tenant)
        val key = UUID.randomUUID().toString()
        val first = r.set(connection, "70", user, key = key)
        first.status shouldBe 200
        val second = r.set(connection, "70", user, key = key)
        second.status shouldBe 200
        second.body shouldBe first.body
        r.stored(tenant, connection).shouldBeNumber("70")
    }

    private companion object {
        const val CONNECTIONS = "/internal/v1/transport/connections"
        const val UNIQUE = 12
    }
}
