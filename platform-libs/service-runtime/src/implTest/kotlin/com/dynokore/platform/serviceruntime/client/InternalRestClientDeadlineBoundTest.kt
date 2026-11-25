package com.dynokore.platform.serviceruntime.client

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilientEdge
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * INV-AC-28249: the bound ends the underlying request, not only the caller's wait. A real HTTP stub (the JDK's own
 * server) answers after a delay or, for case (g), holds its answer on a latch; the request is made through the
 * `internalRestClientBuilder` bean.
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

    /** A stub answer held on a latch: [entered] counts down when the request reaches the stub, [answered] is set when it responds. */
    private class Hold {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val answered = AtomicBoolean(false)
    }

    private fun stub(
        delayMs: Long,
        hold: Hold? = null,
    ): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            if (hold == null) {
                Thread.sleep(delayMs)
            } else {
                hold.entered.countDown()
                hold.release.await(HOLD_SECONDS, TimeUnit.SECONDS)
            }
            val body = "answer".toByteArray()
            runCatching {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                hold?.answered?.set(true)
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

    private fun edge(policyTimeout: Duration = Duration.ofSeconds(5)) =
        Resilience4jResilientEdge(
            "edge-${System.nanoTime()}",
            EdgePolicy(
                timeout = TimeoutPolicy(policyTimeout),
                retry = null,
                circuitBreaker = CircuitBreakerPolicy(50F, 10, Duration.ofSeconds(10), 1),
                bulkhead = BulkheadPolicy(4, Duration.ofMillis(200)),
            ),
            clock,
        )

    // The stub holds its answer on a latch and the policy timeout is 1 hour, so only the deadline can end the call; no
    // assertion compares a measured elapsed time with a constant. The 5 s deadline is a test fixture value, not a
    // product setting: an allowance for the request to be in flight on a slow runner, after a warm-up on the same path.
    @Test
    @Timeout(value = SAFETY_SECONDS, unit = TimeUnit.SECONDS)
    fun `INV-AC-28249 INV-AC-28656 (g) a request slower than the deadline ends at the bound and its thread finishes`() {
        val hold = Hold()
        try {
            runner.run { context ->
                val builder = context.getBean(RestClient.Builder::class.java)
                val edge = edge(Duration.ofHours(1))

                val warmUpClient = builder.baseUrl(stub(0)).build()
                edge.execute(
                    Deadline.after(Duration.ofSeconds(SAFETY_SECONDS), clock),
                    onCallNotPermitted = { "not-permitted" },
                    onConnectFailure = { "connect-failure" },
                    onTimeout = { TIMED_OUT },
                ) {
                    warmUpClient
                        .get()
                        .uri("/")
                        .retrieve()
                        .body(String::class.java)
                        .orEmpty()
                } shouldBe "answer"

                val client = builder.baseUrl(stub(0, hold)).build()
                val finished = CountDownLatch(1)

                val outcome =
                    edge.execute(
                        Deadline.after(Duration.ofSeconds(5), clock),
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

                outcome shouldBe TIMED_OUT
                hold.entered.await(SAFETY_SECONDS, TimeUnit.SECONDS) shouldBe true
                finished.await(SAFETY_SECONDS, TimeUnit.SECONDS) shouldBe true
                hold.answered.get() shouldBe false
            }
        } finally {
            hold.release.countDown()
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

        // Ceilings (12 and 24 times the 5 s deadline of case (g), a test fixture value), not elapsed-time assertions.
        const val SAFETY_SECONDS = 60L
        const val HOLD_SECONDS = 120L
    }
}
