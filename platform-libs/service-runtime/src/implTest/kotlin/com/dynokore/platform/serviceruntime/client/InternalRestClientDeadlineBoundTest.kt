package com.dynokore.platform.serviceruntime.client

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilientEdge
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * INV-AC-28249: the bound ends the underlying request, not only the caller's wait. A real HTTP stub (the JDK's own
 * server) answers after a delay; the request is made through the `internalRestClientBuilder` bean.
 */
class InternalRestClientDeadlineBoundTest {
    private val clock: Clock = Clock.systemUTC()
    private val servers = mutableListOf<HttpServer>()

    /** Warm class loading and the JDK HTTP client untimed, so the timed case measures the bound, not a cold JVM. */
    @BeforeEach
    fun warmUp() {
        runner.run { context ->
            val client = context.getBean(RestClient.Builder::class.java).baseUrl(stub(0)).build()
            client
                .get()
                .uri("/")
                .retrieve()
                .body(String::class.java)
        }
    }

    @AfterEach
    fun stopServers() = servers.forEach { it.stop(0) }

    @Configuration(proxyBeanMethods = false)
    class ClockConfiguration {
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ClockConfiguration::class.java)
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))

    private fun stub(delayMs: Long): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(delayMs)
            val body = "answer".toByteArray()
            runCatching {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.executor =
            java.util.concurrent.Executors
                .newCachedThreadPool()
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun edge() =
        Resilience4jResilientEdge(
            "edge-${System.nanoTime()}",
            EdgePolicy(
                timeout = TimeoutPolicy(Duration.ofSeconds(5)),
                retry = null,
                circuitBreaker = CircuitBreakerPolicy(50F, 10, Duration.ofSeconds(10), 1),
                bulkhead = BulkheadPolicy(4, Duration.ofMillis(200)),
            ),
            clock,
        )

    // Timing subject: a real socket read timeout and time limiter, so the wall clock at the MARGIN_MS / END_MARGIN_MS caps.
    @Test
    fun `INV-AC-28249 (g) a request slower than the deadline ends at the bound and its thread finishes`() {
        runner.run { context ->
            val client = context.getBean(RestClient.Builder::class.java).baseUrl(stub(SLOW_MS)).build()
            val finished = CountDownLatch(1)
            val started = System.nanoTime()

            val outcome =
                edge().execute(
                    Deadline.after(Duration.ofMillis(300), clock),
                    onCallNotPermitted = { "not-permitted" },
                    onConnectFailure = { "connect-failure" },
                    onTimeout = { TIMED_OUT },
                ) {
                    try {
                        client
                            .get()
                            .uri("/")
                            .retrieve()
                            .body(String::class.java)
                            .orEmpty()
                    } finally {
                        finished.countDown()
                    }
                }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            outcome shouldBe TIMED_OUT
            elapsed shouldBeLessThan 300 + MARGIN_MS
            finished.await(300 + END_MARGIN_MS - elapsed, TimeUnit.MILLISECONDS) shouldBe true
        }
    }

    @Test
    fun `INV-AC-28249 (h) with no deadline bound the request returns the stub's answer`() {
        runner.run { context ->
            val client = context.getBean(RestClient.Builder::class.java).baseUrl(stub(50)).build()

            client
                .get()
                .uri("/")
                .retrieve()
                .body(String::class.java) shouldBe "answer"
        }
    }

    @Test
    fun `INV-AC-28249 (i) with a generous deadline bound the request returns the stub's answer`() {
        runner.run { context ->
            val client = context.getBean(RestClient.Builder::class.java).baseUrl(stub(50)).build()

            CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(30), clock)) {
                client
                    .get()
                    .uri("/")
                    .retrieve()
                    .body(String::class.java)
            } shouldBe "answer"
        }
    }

    @Test
    fun `INV-AC-28249 (j) a service's own internalRestClientBuilder bean is used when present`() {
        val own = RestClient.builder()
        runner
            .withBean("internalRestClientBuilder", RestClient.Builder::class.java, { own })
            .run { context ->
                context.getBean("internalRestClientBuilder") shouldBe own
            }
    }

    private companion object {
        const val TIMED_OUT = "timed-out"

        // Caller release: scheduling of a loaded CI runner; the spec caps it at 150 ms.
        const val MARGIN_MS = 150L

        // The request thread unwinds after the interrupt / read timeout (socket teardown); the spec caps it at 500 ms.
        const val END_MARGIN_MS = 500L

        const val SLOW_MS = 2_000L
    }
}
