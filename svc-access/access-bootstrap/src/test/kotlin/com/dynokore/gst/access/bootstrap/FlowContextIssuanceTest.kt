package com.dynokore.gst.access.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.access.adapters.http.IssueFlowContextOperation
import com.dynokore.gst.access.application.flowcontext.FLOW_CONTEXT_LIFETIME_SECONDS
import com.dynokore.gst.access.application.flowcontext.FlowContextClaims
import com.dynokore.gst.access.application.flowcontext.FlowContextSigner
import com.dynokore.gst.access.application.flowcontext.FlowEntityKind
import com.dynokore.gst.access.application.flowcontext.FlowPrincipalKind
import com.dynokore.gst.access.application.flowcontext.IssueFlowContextUseCase
import com.dynokore.gst.access.bootstrap.served.ContractDocument
import com.dynokore.gst.access.contract.model.IssueFlowContextRequest
import com.dynokore.gst.access.contract.model.IssueFlowContextRequestEntity
import com.dynokore.gst.access.contract.model.VerificationKeysResponseKeysInner
import com.dynokore.platform.serviceruntime.identity.FlowContext
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-AC-24990 (INV-T-24991): `issueFlowContext` through the HTTP operation with the real wiring over a real
 * PostgreSQL: the controller of the generated interface, the signer with the key and the registry of the token tests,
 * the injected clock and the settable verified caller of [BoundaryTestConfiguration].
 */
class FlowContextIssuanceTest {
    private val mapper = JsonMapper.builder().build()

    private fun refusedWith(
        env: FlowEnv,
        response: MockHttpServletResponse,
        status: Int,
        code: String?,
    ) {
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(status)
        if (code != null) assertThat(response.contentAsString).contains(code)
        assertThat(response.contentAsString).doesNotContain("flowContext")
        assertThat(env.signs.signs.get()).describedAs("nothing is signed").isZero()
    }

    private fun assertIssuedLine(
        env: FlowEnv,
        compact: String,
    ) {
        // one log line, each field compared by equality; the compact form and the signature are never logged
        val lines = env.lines()
        assertThat(lines).hasSize(1)
        assertThat(lines.single())
            .containsEntry("callerService", "ingestion-svc")
            .containsEntry("flowId", "FLW-1")
            .containsEntry("tenant", env.tenant)
            .containsEntry("entityKind", "ENTITY")
            .containsEntry("entityRef", "ENT-1")
            .containsEntry("principalKind", "HUMAN")
            .containsEntry("result", "ISSUED")
        assertThat(lines.single().keys)
            .containsExactlyInAnyOrder(
                "callerService",
                "flowId",
                "tenant",
                "entityKind",
                "entityRef",
                "principalKind",
                "result",
            )
        val signature = compact.substringAfterLast('.')
        assertThat(env.lineTexts().joinToString()).doesNotContain(compact).doesNotContain(signature)
        assertThat(lines.single().values.map { it.toString() }).noneMatch { it.contains(signature) }
    }

    @Test
    fun `INV-AC-24990 (1) ingestion-svc is issued a flow context that verifies under the published key`() =
        withFlowEnv { env ->
            val now = env.clock.freeze()
            val response = env.call(env.body())
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            val compact = env.flowContextOf(response)
            val jwt = SignedJWT.parse(compact)
            assertThat(jwt.header.type.type).isEqualTo("flow-context+jwt")
            assertThat(env.flowFindings(compact)).isEmpty()
            val claims = env.claims(compact)
            assertThat(claims["tenant"]).isEqualTo(env.tenant)
            assertThat(claims["entity"]).isEqualTo(mapOf("kind" to "ENTITY", "ref" to "ENT-1"))
            assertThat(claims["principalKind"]).isEqualTo("HUMAN")
            assertThat(claims["callerService"]).isEqualTo("ingestion-svc")
            assertThat(claims["flowId"]).isEqualTo("FLW-1")
            val iat =
                SignedJWT
                    .parse(compact)
                    .jwtClaimsSet.issueTime
                    .toInstant()
                    .epochSecond
            val exp =
                SignedJWT
                    .parse(compact)
                    .jwtClaimsSet.expirationTime
                    .toInstant()
                    .epochSecond
            assertThat(iat).isEqualTo(now.epochSecond)
            assertThat(exp - iat).isEqualTo(900L)
            assertThat(mapper.readTree(response.contentAsString).get("expiresAt").asLong()).isEqualTo(exp)
            // a lifetime of 901 is never produced
            assertThat(exp - iat).isLessThanOrEqualTo(900L)
            assertIssuedLine(env, compact)
        }

    @Test
    fun `INV-AC-24990 (2) control-plane-svc is issued a tenant-wide flow context with no ref`() =
        withFlowEnv { env ->
            env.clock.freeze()
            val response =
                env.call(
                    env.body(kind = "TENANT_WIDE", ref = null, principal = "MACHINE"),
                    "control-plane-svc",
                )
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            val compact = env.flowContextOf(response)
            assertThat(env.flowFindings(compact)).isEmpty()
            val claims = env.claims(compact)
            assertThat(claims["entity"]).isEqualTo(mapOf("kind" to "TENANT_WIDE"))
            assertThat(claims["callerService"]).isEqualTo("control-plane-svc")
            assertThat(claims["principalKind"]).isEqualTo("MACHINE")
            assertThat(env.lines().single()).containsEntry("result", "ISSUED").containsEntry("entityRef", null)
        }

    @Test
    fun `INV-AC-24990 (3) submission-svc is refused 403 SRT-004 and nothing is issued`() =
        withFlowEnv { env ->
            refusedWith(env, env.call(env.body(), "submission-svc"), 403, "SRT-004")
            assertThat(env.lines().single()).containsEntry("result", "CALLER_NOT_SERVED")
        }

    @Test
    fun `INV-AC-24990 look-alike callers ingestion-svc-x and Ingestion-Svc are refused 403`() =
        withFlowEnv { env ->
            listOf("ingestion-svc-x", "Ingestion-Svc", "ingestion-svc ", "x-ingestion-svc").forEach {
                refusedWith(env, env.call(env.body(), it), 403, "SRT-004")
            }
        }

    @Test
    fun `INV-AC-24990 (4) no verified caller is refused 403 SRT-004 and nothing is issued`() =
        withFlowEnv { env ->
            // a header that names a served caller changes nothing: the caller is read from the verified identity only
            caller(env)
            // the token tenant is bound, so only the missing caller can refuse this call
            val response =
                env.asTokenTenant {
                    env.mvc
                        .perform(
                            post(FLOW_CONTEXTS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Caller-Service", "ingestion-svc")
                                .param("callerService", "ingestion-svc")
                                .content(env.body()),
                        ).andReturn()
                        .response
                }
            refusedWith(env, response, 403, "SRT-004")
            assertThat(env.lines().single()).containsEntry("result", "CALLER_NOT_SERVED")
        }

    @Test
    fun `INV-AC-24990 tenant equal to the propagated token tenant is issued`() =
        withFlowEnv { env ->
            val response = env.call(env.body(), bound = FlowEnv.Bound.OWN)
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            assertThat(env.signs.signs.get()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-24990 tenant other than the token tenant is refused 403 SRT-004 and nothing is signed`() =
        withFlowEnv { env ->
            refusedWith(env, env.call(env.body(), bound = FlowEnv.Bound.OTHER), 403, "SRT-004")
            assertThat(env.lines().single()).containsEntry("result", "TENANT_MISMATCH")
            // case (9) as written: the request tenant is T2 while the bound tenant is T1
            refusedWith(env, env.call(env.body(tenantText = OTHER_TENANT)), 403, "SRT-004")
            assertThat(env.lines().map { it["result"] }).containsExactly("TENANT_MISMATCH", "TENANT_MISMATCH")
        }

    @Test
    fun `INV-AC-24990 a token without a tenant is refused 403 SRT-004 and nothing is signed`() =
        withFlowEnv { env ->
            refusedWith(env, env.call(env.body(), bound = FlowEnv.Bound.NONE), 403, "SRT-004")
            refusedWith(env, env.call(env.body(), bound = FlowEnv.Bound.PLATFORM), 403, "SRT-004")
            assertThat(env.lines().map { it["result"] }).containsOnly("TENANT_MISMATCH")
        }

    @Test
    fun `INV-AC-24990 tenant look-alikes - last digit 403, leading space and platform tenant 400`() =
        withFlowEnv { env ->
            val lastDigit = env.tenant.dropLast(1) + (if (env.tenant.last() == 'f') '0' else 'f')
            refusedWith(env, env.call(env.body(tenantText = lastDigit)), 403, "SRT-004")
            refusedWith(env, env.call(env.body(tenantText = " " + env.tenant)), 400, "SRT-001")
            val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
            refusedWith(env, env.call(env.body(tenantText = platform), bound = FlowEnv.Bound.OWN), 400, "SRT-001")
            refusedWith(env, env.call(env.body(tenantText = platform), bound = FlowEnv.Bound.PLATFORM), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-24990 a caller outside the set with a mismatching tenant gets the body of case (3)`() =
        withFlowEnv { env ->
            val notServed = env.call(env.body(), "submission-svc")
            val mismatch = env.call(env.body(tenantText = OTHER_TENANT))
            val outside = env.call(env.body(tenantText = OTHER_TENANT), "submission-svc")
            listOf(notServed, mismatch, outside).forEach { refusedWith(env, it, 403, "SRT-004") }
            // the bodies are equal except for the per-request correlation id, so no body names a tenant
            val withoutId = { r: MockHttpServletResponse ->
                r.contentAsString.replace(Regex("\"correlationId\":\"[^\"]*\""), "")
            }
            assertThat(withoutId(outside)).isEqualTo(withoutId(notServed))
            assertThat(withoutId(mismatch)).isEqualTo(withoutId(notServed))
            assertThat(env.lines().map { it["result"] })
                .containsExactly("CALLER_NOT_SERVED", "TENANT_MISMATCH", "TENANT_MISMATCH")
        }

    @Test
    fun `INV-AC-24990 a mismatching tenant is refused 403 before the content and an absent caller stays not served`() =
        withFlowEnv { env ->
            refusedWith(env, env.call(env.body(ref = " "), bound = FlowEnv.Bound.OTHER), 403, "SRT-004")
            refusedWith(env, env.call(env.body(), null, bound = FlowEnv.Bound.OTHER), 403, "SRT-004")
            assertThat(env.lines().map { it["result"] }).containsExactly("TENANT_MISMATCH", "CALLER_NOT_SERVED")
        }

    private fun caller(env: FlowEnv) {
        env.caller.value = null
    }

    @Test
    fun `INV-AC-24990 (5) a property callerService or exp in the request is refused 400 and nothing is issued`() =
        withFlowEnv { env ->
            listOf(
                ",\"callerService\":\"ingestion-svc\"",
                ",\"exp\":9999999999",
                ",\"iat\":1",
                ",\"x\":1",
            ).forEach { refusedWith(env, env.call(env.body(extra = it)), 400, "SRT-001") }
        }

    @Test
    fun `INV-AC-24990 (6) an ENTITY without ref, the platform tenant or a blank ref is refused 400`() =
        withFlowEnv { env ->
            refusedWith(env, env.call(env.body(ref = null)), 400, "SRT-001")
            refusedWith(env, env.call(env.body(tenantText = "ffffffff-ffff-ffff-ffff-ffffffffffff")), 400, "SRT-001")
            // an ENTITY request with the ref of one space is refused
            refusedWith(env, env.call(env.body(ref = " ")), 400, "SRT-001")
            refusedWith(env, env.call(env.body(tenantText = "not-a-tenant")), 400, "SRT-001")
            refusedWith(env, env.call(env.body(kind = "TENANT_WIDE", ref = "ENT-1")), 400, "SRT-001")
            // a missing field and an unknown enum value never reach the operation
            refusedWith(env, env.call("""{"tenant":"${env.tenant}"}"""), 400, "SRT-001")
        }

    @Test
    fun `INV-AC-24990 (7) an access-svc with no signing key answers 503 and issues nothing`() =
        withFlowEnv(mountedKey = false) { env ->
            val response = env.call(env.body())
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(503)
            assertThat(response.contentAsString).contains("SRT-013").doesNotContain("flowContext")
            assertThat(env.lines().single()).containsEntry("result", "SIGNING_UNAVAILABLE")
        }

    @Test
    fun `INV-AC-24990 (8) the output of (1) is refused as an access token and an access token as a flow context`() =
        withFlowEnv { env ->
            val now = env.clock.freeze()
            val compact = env.flowContextOf(env.call(env.body()))
            val keys = env.published()
            assertThat(env.tokens.verify(compact, keys, now)).contains("typ")
            env.tokens.kit.entity("ENT-1")
            val key = env.tokens.kit.newKey("client-key-" + java.util.UUID.randomUUID())
            val created = env.tokens.kit.create("INGEST", scope = "entity:ENT-1", method = PrincipalKit.JWT, key = key)
            val issued = env.tokens.issue(created.principalRef, env.tokens.kit.proof(key, created.principalRef))
            val access = issued.accessToken!!
            assertThat(env.tokens.verify(access, env.published(), now)).isEmpty()
            assertThat(env.flowFindings(access, env.published())).contains("typ")
            // the access-token signing path never produces the flow-context typ
            assertThat(
                SignedJWT
                    .parse(access)
                    .header.type.type,
            ).isEqualTo("at+jwt")

            // a client assertion typed as a flow context is refused although its signature and subject are valid
            fun assertion(typ: String): String {
                val claims =
                    com.nimbusds.jwt.JWTClaimsSet
                        .Builder()
                        .subject(created.principalRef)
                        .expirationTime(java.util.Date.from(now.plusSeconds(300)))
                        .build()
                val header =
                    com.nimbusds.jose.JWSHeader
                        .Builder(com.nimbusds.jose.JWSAlgorithm.ES256)
                        .type(com.nimbusds.jose.JOSEObjectType(typ))
                        .keyID(key.keyID)
                        .build()
                return SignedJWT(header, claims)
                    .also {
                        it.sign(
                            com.nimbusds.jose.crypto
                                .ECDSASigner(key),
                        )
                    }.serialize()
            }
            val typed =
                env.tokens.kit.authenticate(
                    created.principalRef,
                    PrincipalKit.AUTH_JWT,
                    assertion("flow-context+jwt"),
                )
            assertThat(typed.decision.name).isEqualTo("REFUSED")
            val plain = env.tokens.kit.authenticate(created.principalRef, PrincipalKit.AUTH_JWT, assertion("JWT"))
            assertThat(plain.decision.name).isEqualTo("AUTHENTICATED")
        }

    @Test
    fun `INV-AC-24990 a flow context verified with a key of another kid fails`() =
        withFlowEnv { env ->
            val compact = env.flowContextOf(env.call(env.body()))
            val other = TestSecrets.newSigningKey()
            val jwt = SignedJWT.parse(compact)
            assertThat(jwt.verify(ECDSAVerifier(other.toECPublicKey()))).isFalse()
            val keys = env.published()
            val forged = keys.map { VerificationKeysResponseKeysInner("another-kid", it.alg, it.publicKey) }
            assertThat(env.flowFindings(compact, forged)).contains("key")
            val swapped = keys.map { VerificationKeysResponseKeysInner(it.kid, it.alg, other.toECPublicKey().encoded) }
            assertThat(env.flowFindings(compact, swapped)).contains("signature")
            // one key signs both kinds: the published set holds exactly the key of the secret, no second key
            assertThat(env.published().map { it.publicKey.toList() })
                .containsExactly(
                    env.key
                        .toECPublicKey()
                        .encoded
                        .toList(),
                )
        }

    @Test
    fun `INV-AC-24990 each of the seven kind values sent through the operation is the payload text unchanged`() =
        withFlowEnv { env ->
            env.clock.freeze()
            IssueFlowContextRequest.PrincipalKind.entries.forEach { kind ->
                val response = env.call(env.body(principal = kind.value))
                assertThat(response.status).describedAs(kind.value + response.contentAsString).isEqualTo(200)
                assertThat(env.claims(env.flowContextOf(response))["principalKind"]).isEqualTo(kind.value)
            }
            IssueFlowContextRequestEntity.Kind.entries.forEach { kind ->
                val ref = "ENT-1".takeIf { kind == IssueFlowContextRequestEntity.Kind.ENTITY }
                val response = env.call(env.body(kind = kind.value, ref = ref))
                assertThat(response.status).describedAs(kind.value + response.contentAsString).isEqualTo(200)
                @Suppress("UNCHECKED_CAST")
                val entity = env.claims(env.flowContextOf(response))["entity"] as Map<String, Any?>
                assertThat(entity["kind"]).isEqualTo(kind.value)
            }
            // a lower-case look-alike is refused and is not mapped to HUMAN
            val before = env.signs.signs.get()
            val lower = env.call(env.body(principal = "human"))
            assertThat(lower.status).describedAs(lower.contentAsString).isEqualTo(400)
            assertThat(env.call(env.body(kind = "entity")).status).isEqualTo(400)
            assertThat(env.signs.signs.get()).isEqualTo(before)
        }
}
