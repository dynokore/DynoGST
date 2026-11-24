package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKeysChanged
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

private const val T = "aaaaaaaa-0000-0000-0000-000000000001"
private const val CALLER_SVC = "control-plane-svc"
private const val CONTEXT_ROWS = "operationId\tmethod\tpath\ncloseTenant\tPOST\t/v1/closeTenant\n"

/**
 * INV-AC-24992: a service verifies a flow context with the keys it already holds for access-svc tokens (the
 * machine-access set of its authority replica) and requires `typ` `flow-context+jwt`; a flow entry obtains its context
 * from a stub of access-svc through the issuer port; a token of the other kind is refused both ways.
 */
class FlowContextAdaptersTest {
    private val now = FlowContextTestSupport.NOW
    private val clock = FlowContextTestSupport.CLOCK
    private val held = rsa()
    private val outside = rsa()
    private val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
    private val keys = AccessKeysFlowContextKeys(replica)
    private var server: HttpServer? = null

    init {
        replica.keySets.accept(
            OperatorSessionKeysChanged(
                listOf(
                    OperatorSessionKey(
                        "FK",
                        "RS256",
                        Base64.getEncoder().encodeToString(held.public.encoded),
                        now.minusSeconds(60),
                        now.plusSeconds(3600),
                        "MACHINE_ACCESS",
                    ),
                ),
            ),
        )
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun payload(
        tenant: String = T,
        caller: String = CALLER_SVC,
    ) = "{\"tenant\":\"$tenant\",\"entity\":{\"kind\":\"ENTITY\",\"ref\":\"E-1\"},\"principalKind\":\"HUMAN\"," +
        "\"callerService\":\"$caller\",\"flowId\":\"f-1\",\"iat\":${now.epochSecond},\"exp\":${now.epochSecond + 300}}"

    private fun compact(
        typ: String? = FlowContext.TYP,
        signer: KeyPair = held,
        kid: String? = "FK",
        alg: JWSAlgorithm = JWSAlgorithm.RS256,
        body: String = payload(),
    ): String {
        val header =
            JWSHeader
                .Builder(alg)
                .apply { kid?.let(::keyID) }
                .apply { typ?.let { type(JOSEObjectType(it)) } }
                .build()
        val jwt = JWSObject(header, Payload(body))
        jwt.sign(RSASSASigner(signer.private))
        return jwt.serialize()
    }

    @Test
    fun `INV-AC-24992 (1) a context signed by the held key with typ flow-context+jwt verifies to its payload bytes`() {
        keys.verify(compact())?.toString(Charsets.UTF_8) shouldBe payload()
    }

    @Test
    fun `INV-AC-24992 (2) an access token typ JWT is refused with null`() {
        keys.verify(compact(typ = "JWT")) shouldBe null
        keys.verify(compact(typ = "at+jwt")) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) alg none is refused with null`() {
        val plain =
            PlainJWT(
                PlainHeader.Builder().type(JOSEObjectType(FlowContext.TYP)).build(),
                JWTClaimsSet.Builder().build(),
            )
        keys.verify(plain.serialize()) shouldBe null
        keys.verify(compact().substringBeforeLast('.') + ".") shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) a key outside the held set and a bad signature are refused with null`() {
        keys.verify(compact(signer = outside)) shouldBe null
        keys.verify(compact(kid = "OTHER")) shouldBe null
        keys.verify(compact(kid = null)) shouldBe null
        val parts = compact().split('.')
        val flipped = Base64URL.encode(ByteArray(256) { 1 }).toString()
        keys.verify("${parts[0]}.${parts[1]}.$flipped") shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) a missing typ and a trailing space in typ are refused with null`() {
        keys.verify(compact(typ = null)) shouldBe null
        keys.verify(compact(typ = "flow-context+jwt ")) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) look-alike typ values are refused with null, each alone`() {
        keys.verify(compact(typ = "flow-context+jws")) shouldBe null
        keys.verify(compact(typ = "Flow-Context+JWT")) shouldBe null
        keys.verify(compact(typ = "flow-context+jwt-x")) shouldBe null
        keys.verify(compact(typ = "xflow-context+jwt")) shouldBe null
        keys.verify(compact(typ = "")) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) an access token signed by the same key is refused with null`() {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("u")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("FK")
                .type(JOSEObjectType("at+jwt"))
                .build()
        val jwt = SignedJWT(header, claims).also { it.sign(RSASSASigner(held.private)) }
        keys.verify(jwt.serialize()) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) the compact form of one context with its payload swapped is refused with null`() {
        val a = compact(body = payload(tenant = T)).split('.')
        val b = compact(body = payload(tenant = "aaaaaaaa-0000-0000-0000-000000000002")).split('.')
        keys.verify("${a[0]}.${b[1]}.${a[2]}") shouldBe null
        (keys.verify(a.joinToString(".")) != null) shouldBe true
    }

    @Test
    fun `INV-AC-24992 (2) malformed input and no replica give null and never throw`() {
        listOf("", "a", "a.b", "a.b.c", "..", "x.y.z.w", compact().replace('.', ' ')).forEach {
            keys.verify(it) shouldBe
                null
        }
        AccessKeysFlowContextKeys(null).verify(compact()) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (2) a key of another set is not a held access-svc key for flow contexts`() {
        val operatorOnly = AuthorityReplica(clock, OperationRoleMap { emptyMap() })
        operatorOnly.keySets.accept(
            OperatorSessionKeysChanged(
                listOf(
                    OperatorSessionKey(
                        "FK",
                        "RS256",
                        Base64.getEncoder().encodeToString(held.public.encoded),
                        now.minusSeconds(60),
                        now.plusSeconds(3600),
                        "OPERATOR_SESSION",
                    ),
                ),
            ),
        )
        AccessKeysFlowContextKeys(operatorOnly).verify(compact()) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (3) the person-token verifier refuses the compact form of a flow context`() {
        val resolver = JwtEndUserPrincipalResolver(acceptingDecoder, replica, clock)
        val person = personToken(typ = null)
        (resolver.resolve("Bearer $person") != null) shouldBe true
        resolver.resolve("Bearer " + personToken(typ = FlowContext.TYP)) shouldBe null
    }

    @Test
    fun `INV-AC-24992 (3) the workload-token verifier refuses the compact form of a flow context`() {
        val verifier = JwtWorkloadTokenVerifier(acceptingDecoder)
        verifier.verify(WorkloadToken(personToken(typ = null))) shouldBe WorkloadVerification.Verified("user-1")
        verifier.verify(WorkloadToken(personToken(typ = FlowContext.TYP))) shouldBe
            WorkloadVerification.Refused("invalid")
    }

    /**
     * A decoder that accepts every token with the subject `user-1`, whatever its `typ`: the refusal of a flow context
     * then comes from the verifier's own typ guard, not from the decoder's type verifier.
     */
    private val acceptingDecoder =
        JwtDecoder { token ->
            Jwt
                .withTokenValue(token)
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
        }

    private fun personToken(typ: String?): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("user-1")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build()
        val header =
            JWSHeader
                .Builder(
                    JWSAlgorithm.RS256,
                ).keyID("FK")
                .apply { typ?.let { type(JOSEObjectType(it)) } }
                .build()
        return SignedJWT(header, claims).also { it.sign(RSASSASigner(held.private)) }.serialize()
    }

    // ---- the issuer adapter against a stub of access-svc

    private class Seen(
        val body: String,
        val workloadToken: String?,
    )

    private val seen = AtomicReference<Seen?>()

    private fun stub(
        status: Int = 200,
        answer: (String) -> String = { _ -> "{}" },
        delayMillis: Long = 0,
    ): IssuedFlowContextClient {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext(AccessSvcFlowContextIssuer.PATH) { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            seen.set(Seen(body, exchange.requestHeaders.getFirst(WorkloadToken.HEADER_NAME)))
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val out = answer(body).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, out.size.toLong())
            exchange.responseBody.use { it.write(out) }
        }
        http.start()
        server = http
        val factory = SimpleClientHttpRequestFactory().apply { setReadTimeout(300) }
        val client =
            RestClient
                .builder()
                .requestFactory(factory)
                .baseUrl("http://127.0.0.1:${http.address.port}")
                .requestInterceptor(WorkloadTokenPropagationInterceptor { WorkloadToken("workload-abc") })
                .build()
        return IssuedFlowContextClient(AccessSvcFlowContextIssuer(client))
    }

    private class IssuedFlowContextClient(
        val issuer: FlowContextIssuer,
    )

    private fun begin(issuer: FlowContextIssuer) =
        FlowContext.begin(
            issuer,
            clock,
            T,
            FlowEntity.entity("E-1"),
            "HUMAN",
            CALLER_SVC,
            flowId = "f-1",
        )

    private fun okAnswer(expiresAt: Long = now.epochSecond + 900) =
        "{\"flowContext\":\"${compact(body = payload())}\",\"expiresAt\":$expiresAt}"

    @Test
    fun `INV-AC-24992 (4) begin returns the context of the 200 answer and sends only the claims of the port`() {
        val context = begin(stub(answer = { okAnswer() }).issuer)
        context.tenant shouldBe T
        context.entity shouldBe FlowEntity.entity("E-1")
        context.principalKind shouldBe "HUMAN"
        context.callerService shouldBe CALLER_SVC
        context.flowId shouldBe "f-1"
        context.expiresAt shouldBe now.epochSecond + 900
        val sent = checkNotNull(seen.get())
        sent.workloadToken shouldBe "workload-abc"
        listOf("\"iat\"", "\"exp\"", "\"callerService\"").forEach { sent.body.contains(it) shouldBe false }
        sent.body shouldBe
            "{\"tenant\":\"$T\",\"entity\":{\"kind\":\"ENTITY\",\"ref\":\"E-1\"},\"principalKind\":\"HUMAN\"," +
            "\"flowId\":\"f-1\"}"
    }

    @Test
    fun `INV-AC-24992 (5) a 503 answer throws the refusal and no context is held`() {
        val issuer = stub(status = 503, answer = { "{\"code\":\"SRT-013\"}" }).issuer
        shouldThrow<FlowContextUnavailableException> { begin(issuer) }
        FlowContext.current() shouldBe null
    }

    @Test
    fun `INV-AC-24992 (5) every non-2xx answer throws the refusal`() {
        listOf(400, 401, 403, 500, 302).forEach { status ->
            shouldThrow<FlowContextUnavailableException> {
                begin(
                    stub(status = status, answer = { okAnswer() }).issuer,
                )
            }
            server?.stop(0)
        }
    }

    @Test
    fun `INV-AC-24992 (5) a timeout throws the refusal`() {
        val issuer = stub(answer = { okAnswer() }, delayMillis = 1500).issuer
        shouldThrow<FlowContextUnavailableException> { begin(issuer) }
    }

    @Test
    fun `INV-AC-24992 (5) an answer without flowContext throws the refusal`() {
        shouldThrow<FlowContextUnavailableException> {
            begin(stub(answer = { "{\"expiresAt\":${now.epochSecond + 900}}" }).issuer)
        }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> {
            begin(stub(answer = { "{\"flowContext\":\"\",\"expiresAt\":1}" }).issuer)
        }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> {
            begin(
                stub(answer = { "{\"flowContext\":7,\"expiresAt\":1}" }).issuer,
            )
        }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> { begin(stub(answer = { "" }).issuer) }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> { begin(stub(answer = { "{\"flowContext\":\"a.b.c\"}" }).issuer) }
    }

    @Test
    fun `INV-AC-24992 (5) an expiry outside the lifetime of access-svc is refused, no context held`() {
        shouldThrow<FlowContextUnavailableException> { begin(stub(answer = { okAnswer(now.epochSecond) }).issuer) }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> { begin(stub(answer = { okAnswer(now.epochSecond - 1) }).issuer) }
        server?.stop(0)
        shouldThrow<FlowContextUnavailableException> {
            begin(
                stub(answer = { okAnswer(now.epochSecond + 961) }).issuer,
            )
        }
        server?.stop(0)
        begin(stub(answer = { okAnswer(now.epochSecond + 960) }).issuer).expiresAt shouldBe now.epochSecond + 960
        server?.stop(0)
        begin(stub(answer = { okAnswer(now.epochSecond + 1) }).issuer).expiresAt shouldBe now.epochSecond + 1
    }

    // ---- (6) the filter with the adapter, and the wiring

    private fun <T : Any> provider(
        type: Class<T>,
        bean: T,
    ): ObjectProvider<T> =
        DefaultListableBeanFactory().also { it.registerSingleton(type.simpleName, bean) }.getBeanProvider(type)

    private fun call(header: String): Pair<MockHttpServletResponse, Int> {
        val tenants = TenantContextImpl()
        var handled = 0
        val filter =
            FlowContextFilter(
                FlowContextOperations.of(listOf("test" to CONTEXT_ROWS)),
                provider(FlowContextKeys::class.java, keys),
                provider(Clock::class.java, clock),
                provider(TenantContext::class.java, tenants),
                DefaultListableBeanFactory().getBeanProvider(EndUserPrincipalResolver::class.java),
            )
        val request = MockHttpServletRequest("POST", "/v1/closeTenant")
        request.addHeader(FlowContext.HEADER_NAME, header)
        request.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, CALLER_SVC)
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> handled++ })
        return response to handled
    }

    @Test
    fun `INV-AC-24992 (6) the flow-context filter accepts a context the adapter verifies and refuses another typ`() {
        call(compact()).second shouldBe 1
        val refused = call(compact(typ = "JWT"))
        refused.first.status shouldBe 403
        refused.second shouldBe 0
    }

    @Test
    fun `INV-AC-24992 (6) the auto-configuration registers the adapter and a service bean replaces it`() {
        val runner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdentityAutoConfiguration::class.java))
                .withPropertyValues(
                    "service-runtime.workload-identity.audience=canonical-svc",
                    "service-runtime.workload-identity.jwk-set-uri=http://localhost:1/jwks",
                ).withBean(TenantContext::class.java, { TenantContextImpl() })
                .withBean(Clock::class.java, { clock })
        runner.withBean(AuthorityReplica::class.java, { replica }).run {
            (it.getBean(FlowContextKeys::class.java) is AccessKeysFlowContextKeys) shouldBe true
        }
        val own = FlowContextKeys { null }
        runner
            .withBean(
                FlowContextKeys::class.java,
                { own },
            ).run { it.getBean(FlowContextKeys::class.java) shouldBe own }
    }
}
