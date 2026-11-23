package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

/**
 * Accepts the workload token `token-console-bff` only, so the end-user path of the production wiring is the one tested.
 * Not annotated as a configuration: the application's component scan covers this package, so only the test's own
 * registration puts it in a context.
 */
class WorkloadTokensOnly {
    @Bean
    @Primary
    fun consoleBffTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            if (token?.value == "token-console-bff") {
                WorkloadVerification.Verified("sa-console-bff")
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }
}

/**
 * INV-AC-2510 and INV-AC-2511 (INV-T-26178): the production wiring of notification-svc binds the
 * `IdentityProvider` from required properties; `setPolicy` of the planned VP-67 and VP-68 is refused
 * 422 POL-006 by `PolicyChecks.settable`. service-runtime 12.0.0 decides the permission key `vp-NN.set` first
 * (INV-Q-10027), so the end user holds a tenant-scoped grant of a role the generated operation-role map lists
 * for that key; the grant does not change the refusal. The token is signed by a test key whose key set the test
 * serves at `jwk-set-uri`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions")
class ProductionIdentityTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var key: RSAKey
    private lateinit var otherKey: RSAKey
    private lateinit var jwks: HttpServer
    private lateinit var issuer: String
    private lateinit var jwkSetUri: String

    @BeforeAll
    fun startKeyServer() {
        key = RSAKeyGenerator(2048).keyID("k1").generate()
        otherKey = RSAKeyGenerator(2048).keyID("k1").generate() // the same kid, another key: a forged signature
        jwks = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
        jwks.createContext("/jwks") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        jwks.start()
        val base = "http://127.0.0.1:${jwks.address.port}"
        issuer = "$base/realms/test"
        jwkSetUri = "$base/jwks"
    }

    @AfterAll
    fun stopKeyServer() = jwks.stop(0)

    // one defaulted parameter per claim or key a case varies
    @Suppress("LongParameterList")
    private fun token(
        tenant: TenantId?,
        aud: String = AUDIENCE,
        iss: String = issuer,
        expires: Instant = Instant.now().plusSeconds(300),
        signWith: RSAKey = key,
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("p1")
                .issuer(iss)
                .audience(aud)
                .apply { tenant?.let { claim("tenant_id", it.toString()) } }
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expires))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims)
        jwt.sign(RSASSASigner(signWith))
        return jwt.serialize()
    }

    /**
     * The Gradle test task supplies the installation values as system properties, which outrank the default properties
     * [NotificationContext.startServlet] sets; this test sets its own for the start and restores the others.
     */
    private fun start(dir: Path): ConfigurableApplicationContext {
        val values =
            mapOf(
                "notification.identity-provider.issuer-uri" to issuer,
                "notification.identity-provider.jwk-set-uri" to jwkSetUri,
                "service-runtime.end-user-identity.audience" to AUDIENCE,
            )
        val saved = values.keys.associateWith { System.getProperty(it) }
        values.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            return startServlet(dir)
        } finally {
            saved.forEach { (name, old) ->
                if (old == null) System.clearProperty(name) else System.setProperty(name, old)
            }
        }
    }

    private fun startServlet(dir: Path): ConfigurableApplicationContext =
        NotificationContext.startServlet(
            dir,
            listOf(WorkloadTokensOnly::class.java),
            arrayOf(
                "notification.identity-provider.issuer-uri=$issuer",
                "notification.identity-provider.jwk-set-uri=$jwkSetUri",
                "service-runtime.end-user-identity.audience=$AUDIENCE",
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                "management.server.port=0",
            ),
            ProductionIdentityTest::class.java.classLoader,
        )

    /** The body of a change of [vp]: the non-default [option] of its catalogue row holding [value]. */
    private fun body(
        tenant: TenantId,
        option: String,
        value: (ObjectNode) -> Unit,
    ): String {
        val node = mapper.createObjectNode()
        node.put("scopeLevel", "TENANT")
        node.put("scopeRef", tenant.toString())
        val selected = node.putArray("selected").addObject()
        selected.put("option", option)
        value(selected)
        node.put("effectiveDate", LocalDate.now().toString())
        node.put("reason", "retry window or reminder change while the variation point is planned")
        return mapper.writeValueAsString(node)
    }

    private fun post(
        context: ConfigurableApplicationContext,
        path: String,
        body: String,
        token: String?,
    ): Pair<Int, String?> {
        val port = context.getBean(Environment::class.java).getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("X-Workload-Token", "token-console-bff")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body()).get("code")?.asString()
    }

    private fun tableExists(): Boolean =
        NotificationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select count(*) from information_schema.tables " +
                            "where table_schema = 'notification' and table_name = 'config_setting'",
                    ).use { rows ->
                        rows.next()
                        rows.getLong(1) == 1L
                    }
            }
        }

    /** The row count of `notification.config_setting`, or null while the table does not exist. */
    private fun settingRows(): Long? =
        if (!tableExists()) {
            null
        } else {
            NotificationDb.superuser().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from notification.config_setting").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }
        }

    /** The end user `p1` holds a tenant-scoped grant of a role the generated map lists for `vp-NN.set`. */
    private fun grantSetter(
        context: ConfigurableApplicationContext,
        tenant: TenantId,
        vp: String,
    ) {
        val role =
            context
                .getBean(OperationRoleMap::class.java)
                .rolesFor(vp.lowercase() + ".set")
                .entries
                .first { it.value == CellAllowance.PERMITTED }
                .key
        val replica = context.getBean(AuthorityReplica::class.java)
        replica.onSnapshotComplete(0)
        replica.accept(
            AuthorityChanged(
                tenant,
                "p1",
                1L,
                AuthorityRecord.RoleGrantRecord(
                    "g-p1-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.TENANT, tenant.toString(), Instant.now().minusSeconds(60)),
                ),
            ),
        )
    }

    /** The refusal of [vp] and every probe; the stored state is equal before and after each call. */
    private fun refused(
        dir: Path,
        vp: String,
        option: String,
        value: (ObjectNode) -> Unit,
    ) {
        start(dir).use { context ->
            context.getBeansOfType(PolicyWriter::class.java).isEmpty() shouldBe false
            val tenant = TenantId(UUID.randomUUID())
            val before = settingRows()
            val path = "/internal/v1/policies/$vp"
            val change = body(tenant, option, value)
            grantSetter(context, tenant, vp)
            val ok = token(tenant)

            post(context, path, change, ok) shouldBe (422 to "POL-006")
            settingRows() shouldBe before

            // probes, each differing from the refusal above
            post(context, "/internal/v1/policies/VP-99", change, ok) shouldBe (404 to "POL-001")
            settingRows() shouldBe before
            val other = TenantId(UUID.randomUUID())
            post(context, path, body(other, option, value), ok) shouldBe (403 to "SRT-004")
            settingRows() shouldBe before
            post(context, path, change, token(null)) shouldBe (403 to "SRT-004")
            settingRows() shouldBe before
            val unauthenticated =
                listOf(
                    token(tenant, aud = "other-svc"),
                    token(tenant, iss = "$issuer-other"),
                    token(tenant, expires = Instant.now().minusSeconds(120)),
                    token(tenant, signWith = otherKey),
                    null,
                )
            for (bad in unauthenticated) {
                post(context, path, change, bad) shouldBe (401 to "SRT-003")
                settingRows() shouldBe before
            }
            // the lookup of the table is unchanged: with no table, nothing can have been stored
            (settingRows() == null) shouldBe (before == null)
        }
    }

    @Test
    fun `INV-AC-2510 a retry window of 48 hours is refused while VP-67 is planned`(
        @TempDir dir: Path,
    ) = refused(dir, "VP-67", "CUSTOM") { it.put("value", 48) }

    @Test
    fun `INV-AC-2511 reminders at 10 and 3 days are refused while VP-68 is planned`(
        @TempDir dir: Path,
    ) = refused(dir, "VP-68", "CUSTOM_DAYS") { node ->
        node.putArray("value").add(10).add(3)
    }

    private fun startupFailure(
        dir: Path,
        vararg properties: String,
    ): String {
        var failure: Throwable? = null
        NotificationContext
            .offline(dir)
            // the Gradle test task's system properties would otherwise supply the issuer and the audience
            .withInitializer { it.environment.propertySources.remove("systemProperties") }
            .withPropertyValues(*properties)
            .run { failure = it.startupFailure }
        val thrown = checkNotNull(failure) { "the context started" }
        return generateSequence(thrown) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `INV-AC-2510 the context does not start when the issuer is missing blank or written as a look-alike`(
        @TempDir dir: Path,
    ) {
        val audience = "service-runtime.end-user-identity.audience=$AUDIENCE"
        startupFailure(dir, audience) shouldContain "notification.identity-provider.issuer-uri"
        startupFailure(dir, audience, "notification.identity-provider.issuer-uri=") shouldContain
            "notification.identity-provider.issuer-uri"
        startupFailure(dir, audience, "notification.identity-provider.issuer-uri=   ") shouldContain
            "notification.identity-provider.issuer-uri"
        startupFailure(
            dir,
            audience,
            "notification.identity-providers.issuer-uri=https://issuer.invalid/realms/test",
        ) shouldContain "notification.identity-provider.issuer-uri"
    }

    @Test
    fun `INV-AC-2510 the context does not start when the audience is missing`(
        @TempDir dir: Path,
    ) {
        startupFailure(
            dir,
            "notification.identity-provider.issuer-uri=https://issuer.invalid/realms/test",
        ) shouldContain "service-runtime.end-user-identity.audience"
    }

    @Test
    fun `INV-AC-2510 no main class implements EndUserPrincipalResolver PolicyRoleResolver or SetterPermission`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val modules =
            listOf("notification-bootstrap", "notification-adapters", "notification-application", "notification-domain")
        val files =
            modules.flatMap { module ->
                val main = root.resolve(module).resolve("src/main")
                check(Files.isDirectory(main)) { "$main is missing" }
                Files.walk(main).use { walk ->
                    walk.filter { it.toString().endsWith(".kt") }.map { it.toString() to Files.readString(it) }.toList()
                }
            }
        files.isNotEmpty() shouldBe true
        findings(files).shouldBeEmpty()
        // probes: an implementation, a lambda and an object expression of each port are found
        for (port in PORTS) {
            findings(listOf("a" to "class A : $port { }")) shouldBe listOf("a")
            findings(listOf("a" to "val r = $port { _ -> null }")) shouldBe listOf("a")
            findings(listOf("a" to "val o = object : $port { }")) shouldBe listOf("a")
        }
        findings(listOf("a" to "// EndUserPrincipalResolver\nclass B")) shouldBe emptyList()
    }

    private fun findings(files: List<Pair<String, String>>): List<String> {
        val ports = Regex("""\b(${PORTS.joinToString("|")})\b""")
        return files.filter { (_, text) -> ports.containsMatchIn(stripComments(text)) }.map { it.first }
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

    private companion object {
        const val AUDIENCE = "notification-svc"
        val PORTS = listOf("EndUserPrincipalResolver", "PolicyRoleResolver", "SetterPermission")
    }
}
