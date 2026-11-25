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
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
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

/** Counts the calls of the flow-context signer port: a refused call must not reach it (nothing issued). */
const val OTHER_TENANT = "00000000-0000-4000-8000-0000000000b2"

class FlowSignCalls {
    val signs = AtomicInteger()
}

class CountingFlowSigner(
    private val delegate: FlowContextSigner,
    private val calls: FlowSignCalls,
) : FlowContextSigner {
    override fun sign(claims: FlowContextClaims): String {
        calls.signs.incrementAndGet()
        return delegate.sign(claims)
    }
}

class FlowSignCounting {
    @Bean
    fun flowSignCalls(): FlowSignCalls = FlowSignCalls()

    @Bean
    fun countingFlowSigner(calls: FlowSignCalls): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is FlowContextSigner &&
                    bean !is CountingFlowSigner
                ) {
                    CountingFlowSigner(bean, calls)
                } else {
                    bean
                }
        }
}

/** The path of the operation, as the contract states it. */
const val FLOW_CONTEXTS_PATH = "/internal/v1/access/flow-contexts"

/** The real context of the service for one `issueFlowContext` test: a mounted key, the clock, the caller, the log. */
class FlowEnv(
    val context: AssertableWebApplicationContext,
    val mounted: Path,
    val key: ECKey,
) {
    private val mapper = JsonMapper.builder().build()

    val tokens = TokenKit(PrincipalKit(context), mounted, context.getBean(SignerCalls::class.java))
    val caller =
        context.getBean(com.dynokore.platform.serviceruntime.identity.ServiceCaller::class.java)
            as MovableServiceCaller
    val signs = context.getBean(FlowSignCalls::class.java)
    val clock = tokens.kit.clock
    val tenant = tokens.kit.tenant.toString()
    val mvc: MockMvc =
        MockMvcBuilders
            .webAppContextSetup(
                context.sourceApplicationContext as WebApplicationContext,
            ).build()
    private val appender =
        ListAppender<ILoggingEvent>().also {
            it.start()
            (LoggerFactory.getLogger(IssueFlowContextOperation::class.java) as Logger).addAppender(it)
        }

    fun close() {
        (LoggerFactory.getLogger(IssueFlowContextOperation::class.java) as Logger).detachAppender(appender)
        tokens.close()
    }

    /** The structured log lines of the operation: the key-value fields of each call. */
    fun lines(): List<Map<String, Any?>> =
        appender.list.map { event -> event.keyValuePairs.associate { it.key to it.value } }

    fun lineTexts(): List<String> = appender.list.map { it.formattedMessage }

    fun body(
        kind: String = "ENTITY",
        ref: String? = "ENT-1",
        principal: String = "HUMAN",
        tenantText: String = tenant,
        extra: String = "",
    ): String {
        val entity = if (ref == null) """{"kind":"$kind"}""" else """{"kind":"$kind","ref":"$ref"}"""
        return """{"tenant":"$tenantText","entity":$entity,"principalKind":"$principal","flowId":"FLW-1"$extra}"""
    }

    /** The token binding of the call: [OWN] the request tenant, [OTHER] another tenant, [NONE], [PLATFORM]. */
    enum class Bound { OWN, OTHER, NONE, PLATFORM }

    private val tenantContext = context.getBean(TenantContext::class.java)
    private val platformBinding = context.getBean(PlatformTenantBinding::class.java)

    /** Runs [perform] with the request tenant bound, as the token of that tenant binds it. */
    fun <T> asTokenTenant(perform: () -> T): T = tenantContext.bind(tokens.kit.tenant, "usr-1", perform)

    fun call(
        body: String,
        service: String? = "ingestion-svc",
        bound: Bound = Bound.OWN,
    ): MockHttpServletResponse {
        caller.value = service
        val perform = {
            mvc
                .perform(post(FLOW_CONTEXTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response
        }
        return when (bound) {
            Bound.OWN -> asTokenTenant(perform)
            Bound.OTHER -> tenantContext.bind(TenantId.of(OTHER_TENANT), "usr-1", perform)
            Bound.PLATFORM -> platformBinding.bindPlatform("usr-1", perform)
            Bound.NONE -> perform()
        }
    }

    fun published(): List<VerificationKeysResponseKeysInner> = tokens.published()

    /** The findings of the flow-context check: the typ, the signature under the published key and the lifetime. */
    fun flowFindings(
        compact: String,
        keys: List<VerificationKeysResponseKeysInner> = published(),
    ): List<String> {
        val jwt = runCatching { SignedJWT.parse(compact) }.getOrElse { return listOf("parse") }
        val found = mutableListOf<String>()
        if (jwt.header.type?.type != "flow-context+jwt") found += "typ"
        val der = keys.firstOrNull { it.kid == jwt.header.keyID && it.alg == jwt.header.algorithm.name }?.publicKey
        if (der == null) {
            found += "key"
        } else if (!jwt.verify(
                ECDSAVerifier(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey),
            )
        ) {
            found += "signature"
        }
        return found
    }

    fun claims(compact: String): Map<String, Any?> = SignedJWT.parse(compact).jwtClaimsSet.claims

    fun flowContextOf(response: MockHttpServletResponse): String =
        mapper.readTree(response.contentAsString).get("flowContext").asString()
}

/** Runs [block] over the real context; [mountedKey] false leaves access-svc with no signing key. */
fun withFlowEnv(
    mountedKey: Boolean = true,
    block: (FlowEnv) -> Unit,
) {
    BoundaryFixture.deleteVerificationKeys()
    val key = TestSecrets.newSigningKey()
    val mounted = BoundaryFixture.mount(key)
    if (!mountedKey) Files.delete(mounted.resolve("access/token-signing-key"))
    BoundaryFixture
        .runner(mounted = mounted)
        .withUserConfiguration(FlowSignCounting::class.java)
        .run { context ->
            context.startupFailure?.let { throw it }
            val env = FlowEnv(context, mounted, key)
            try {
                block(env)
            } finally {
                env.close()
            }
        }
}
