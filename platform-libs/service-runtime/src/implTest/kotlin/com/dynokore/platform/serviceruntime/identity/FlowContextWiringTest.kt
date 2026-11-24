package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.time.Clock

/** P109: the context is built the way a service's bootstrap builds it, with the keys and tenant context as beans. */
class FlowContextWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdentityAutoConfiguration::class.java))
            .withPropertyValues(
                "service-runtime.workload-identity.audience=canonical-svc",
                "service-runtime.workload-identity.jwk-set-uri=http://localhost:1/jwks",
            ).withBean(FlowContextKeys::class.java, { FlowContextTestSupport.keys() })
            .withBean(TenantContext::class.java, { TenantContextImpl() })
            .withBean(Clock::class.java, { FlowContextTestSupport.CLOCK })

    @Test
    fun `INV-AC-8251 the flow-context filter follows the calling-service filter and the interceptor is a bean`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            val filters = context.getBeansOfType(FilterRegistrationBean::class.java).values
            val order = { type: Class<*> -> filters.single { type.isInstance(it.filter) }.order }
            order(SyncEdgeCallerFilter::class.java) shouldBe -85
            order(FlowContextFilter::class.java) shouldBe -84
            IdentityAutoConfiguration.ORDER_FLOW_CONTEXT shouldBe -84
            context.getBeansOfType(ClientHttpRequestInterceptor::class.java).values.any {
                it is FlowContextPropagationInterceptor
            } shouldBe true
            context.getBean(FlowContextOperations::class.java).serving("POST", "/v1/none") shouldBe null
        }
    }

    private val listed =
        FlowContextOperations.of(
            listOf("test" to "operationId\tmethod\tpath\ncloseTenant\tPOST\t/v1/closeTenant\n"),
        )
    private val key = "service-runtime.peers.access-svc.base-url"

    @Test
    fun `INV-AC-24992 a context with a flow entry and no access-svc address fails to start and names the property`() {
        runner.withBean(FlowContextOperations::class.java, { listed }).run { context ->
            val failure = context.startupFailure
            (failure != null) shouldBe true
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(key) } shouldBe true
        }
        runner.withBean(FlowContextOperations::class.java, { listed }).withPropertyValues("$key= ").run { context ->
            (context.startupFailure != null) shouldBe true
        }
    }

    @Test
    fun `INV-AC-24992 the same context starts with the address and registers the access-svc issuer`() {
        runner
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))
            .withBean(FlowContextOperations::class.java, { listed })
            .withPropertyValues("$key=http://access-svc:8080")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(FlowContextIssuer::class.java).shouldBeInstanceOf<AccessSvcFlowContextIssuer>()
            }
    }

    @Test
    fun `INV-AC-24992 a context with no flow entry starts without the address`() {
        runner.withBean(FlowContextOperations::class.java, { FlowContextOperations.NONE }).run { context ->
            context.startupFailure shouldBe null
        }
    }
}
