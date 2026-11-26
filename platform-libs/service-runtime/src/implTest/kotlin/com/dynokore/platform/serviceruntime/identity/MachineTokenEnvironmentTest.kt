package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.util.Base64

private val ME_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")
private const val ME_ISSUER = "https://access.example/"
private const val ME_AUDIENCE = "gst-public-api"
private const val ME_INGEST = "/v1/ingest/a"
private const val ME_CERT = "/v1/ingest/cert"
private const val ME_REPORTS = "/v1/reports"

/**
 * One set of claims added to an otherwise identical token (a JSON `null` value is sent as such), and the principal's
 * environment they give.
 */
private data class EnvironmentCase(
    val added: Map<String, Any?>,
    val expected: StructureEnvironment?,
)

private fun environmentCase(
    value: Any?,
    expected: StructureEnvironment,
) = EnvironmentCase(mapOf("environment" to value), expected)

private val ME_SANDBOX = environmentCase("SANDBOX", StructureEnvironment.SANDBOX)

private val ME_CASES =
    listOf(
        ME_SANDBOX,
        environmentCase("PRODUCTION", StructureEnvironment.PRODUCTION),
        environmentCase("sandbox", StructureEnvironment.UNKNOWN),
        environmentCase("Sandbox", StructureEnvironment.UNKNOWN),
        environmentCase("production", StructureEnvironment.UNKNOWN),
        environmentCase("SANDBOXX", StructureEnvironment.UNKNOWN),
        environmentCase("SANDBOX ", StructureEnvironment.UNKNOWN),
        environmentCase(" SANDBOX", StructureEnvironment.UNKNOWN),
        environmentCase("SANDBOX,PRODUCTION", StructureEnvironment.UNKNOWN),
        environmentCase("UNKNOWN", StructureEnvironment.UNKNOWN),
        environmentCase("*", StructureEnvironment.UNKNOWN),
        environmentCase("", StructureEnvironment.UNKNOWN),
        environmentCase(7, StructureEnvironment.UNKNOWN),
        environmentCase(true, StructureEnvironment.UNKNOWN),
        environmentCase(listOf("SANDBOX"), StructureEnvironment.UNKNOWN),
        environmentCase(emptyList<String>(), StructureEnvironment.UNKNOWN),
        environmentCase(mapOf("environment" to "SANDBOX"), StructureEnvironment.UNKNOWN),
        environmentCase(null, StructureEnvironment.UNKNOWN),
        EnvironmentCase(emptyMap(), null),
        EnvironmentCase(mapOf("Environment" to "SANDBOX"), null),
        EnvironmentCase(mapOf("environments" to "SANDBOX"), null),
    )

/** The environment claim of a verified machine access token on its principal (INV-AC-29094). */
class MachineTokenEnvironmentTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = Instant.now()
    private val replica =
        AuthorityReplica(Clock.systemUTC(), OperationRoleMap { emptyMap() }).apply {
            keySets.accept(
                OperatorSessionKeysChanged(
                    listOf(
                        OperatorSessionKey(
                            "mk",
                            "RS256",
                            Base64.getEncoder().encodeToString(keys.public.encoded),
                            now.minusSeconds(60),
                            now.plusSeconds(3600),
                            "MACHINE_ACCESS",
                        ),
                    ),
                ),
            )
        }
    private val tenants =
        DefaultListableBeanFactory()
            .apply { registerSingleton("tenants", TenantContextImpl()) }
            .getBeanProvider(TenantContext::class.java)
    private val filter =
        MachineTokenFilter(
            MachineTokenVerifier(replica, MachineIdentityProperties(ME_ISSUER, ME_AUDIENCE), Clock.systemUTC()),
            tenants,
            listOf(
                OperationLine("POST", ME_INGEST, MachineOperation("ingest", null)),
                OperationLine("POST", ME_CERT, MachineOperation("ingest", "certification")),
                OperationLine("POST", ME_REPORTS, MachineOperation("reporting", null)),
            ),
        )

    private data class Outcome(
        val status: Int,
        val body: String,
        val handled: List<Principal?>,
    )

    private fun token(
        scope: String,
        added: Map<String, Any?>,
    ): String {
        val claims =
            mapOf<String, Any?>(
                "iss" to ME_ISSUER,
                "aud" to ME_AUDIENCE,
                "sub" to "client-1",
                "client_id" to "client-1",
                "tenant_id" to ME_TENANT.toString(),
                "scope" to scope,
                "principal_scope" to "entity:E1",
                "principal_type" to "filing-workflow",
                "jti" to "jti-1",
                "iat" to now.epochSecond,
                "exp" to now.plusSeconds(600).epochSecond,
            ) + added
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("mk")
                .type(JOSEObjectType("at+jwt"))
                .build()
        // A raw payload, so that a JSON null reaches the verifier instead of being dropped by a claims builder.
        val jws = JWSObject(header, Payload(json(claims)))
        jws.sign(RSASSASigner(keys.private))
        return "Bearer " + jws.serialize()
    }

    private fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${json(it.key)}:${json(it.value)}" }
            is List<*> -> value.joinToString(",", "[", "]") { json(it) }
            else -> value.toString()
        }

    private fun send(
        authorization: String,
        path: String,
    ): Outcome {
        val request = MockHttpServletRequest("POST", path)
        request.addHeader("Authorization", authorization)
        val response = MockHttpServletResponse()
        val handled = mutableListOf<Principal?>()
        val servlet =
            object : HttpServlet() {
                override fun service(
                    req: HttpServletRequest,
                    res: HttpServletResponse,
                ) {
                    handled += req.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal
                    res.status = 200
                }
            }
        MockFilterChain(servlet, filter).doFilter(request, response)
        return Outcome(response.status, response.contentAsString, handled)
    }

    private fun accepted(
        scope: String,
        path: String,
        case: EnvironmentCase,
    ) = withClue("$scope token on $path with ${case.added}") {
        val outcome = send(token(scope, case.added), path)
        outcome.status shouldBe 200
        outcome.handled
            .single()!!
            .environment shouldBe case.expected
    }

    private fun refused(
        scope: String,
        path: String,
        case: EnvironmentCase,
    ) = withClue("$scope token on $path with ${case.added}") {
        val outcome = send(token(scope, case.added), path)
        outcome.status shouldBe 403
        outcome.body shouldContain "\"code\":\"SRT-004\""
        outcome.handled shouldBe emptyList()
    }

    @Test
    fun `INV-AC-29094 an ingest token on a line it serves is accepted whatever the claim and carries it`() {
        ME_CASES.forEach {
            accepted("ingest", ME_INGEST, it)
            accepted("ingest", ME_CERT, it)
        }
    }

    @Test
    fun `INV-AC-29094 an ingest token on a line it does not serve stays 403 SRT-004 whatever the claim`() {
        ME_CASES.forEach { refused("ingest", ME_REPORTS, it) }
    }

    @Test
    fun `INV-AC-29094 a reporting token carries the claim on its line and stays refused on another`() {
        ME_CASES.forEach {
            accepted("reporting", ME_REPORTS, it)
            refused("reporting", ME_INGEST, it)
        }
    }

    @Test
    fun `INV-AC-29094 a certification token is accepted only with the exact value SANDBOX and carries SANDBOX`() {
        accepted("certification", ME_CERT, ME_SANDBOX)
        refused("certification", ME_INGEST, ME_SANDBOX)
        ME_CASES.filter { it != ME_SANDBOX }.forEach {
            refused("certification", ME_CERT, it)
            refused("certification", ME_INGEST, it)
        }
    }

    @Test
    fun `INV-AC-29094 a principal made by the public constructor has no environment`() {
        Principal("u", PrincipalKind.HUMAN).environment shouldBe null
        Principal("a", PrincipalKind.CONSENT_AGENT).environment shouldBe null
    }

    @Test
    fun `INV-AC-29094 the environment is not part of equality, hash code or text of a principal`() {
        val coverage = MachineEntityCoverage.Entity("E1")
        val none = Principal.machine("client-1", ME_TENANT, coverage, "filing-workflow")
        none.environment shouldBe null
        StructureEnvironment.entries.forEach {
            val other = Principal.machine("client-1", ME_TENANT, coverage, "filing-workflow", environment = it)
            other.environment shouldBe it
            other shouldBe none
            other.hashCode() shouldBe none.hashCode()
            other.toString() shouldBe none.toString()
        }
    }
}
