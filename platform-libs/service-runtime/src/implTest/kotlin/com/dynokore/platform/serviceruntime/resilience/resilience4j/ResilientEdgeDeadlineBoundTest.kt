package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.RetryPolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-AC-28249 / INV-REQ-1203: every call is bounded by the smaller of the edge's timeout and the deadline's remainder,
 * the bound ends the attempt, and no attempt or backoff runs once the budget is spent. A real clock, so the deadline
 * really runs down. Each case states below whether its subject is a real timer (wall clock) or only the arithmetic of
 * the deadline's remainder (the controllable [fixedClock]).
 */
class ResilientEdgeDeadlineBoundTest {
    private val clock: Clock = Clock.systemUTC()

    // Injected, controllable clock for the cases whose subject is only `deadline.remaining(clock)` arithmetic.
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC)

    private fun edge(
        timeout: Duration,
        retryAttempts: Int? = null,
        clock: Clock = this.clock,
    ) = Resilience4jResilientEdge(
        "edge-${System.nanoTime()}",
        EdgePolicy(
            timeout = TimeoutPolicy(timeout),
            retry =
                retryAttempts?.let { RetryPolicy(it, Duration.ofMillis(200), Duration.ofMillis(200), jitter = 0.0) },
            circuitBreaker = CircuitBreakerPolicy(50F, 10, Duration.ofSeconds(10), 1),
            bulkhead = BulkheadPolicy(4, Duration.ofMillis(200)),
        ),
        clock,
    )

    private fun Resilience4jResilientEdge.run(
        deadline: Deadline,
        block: () -> String,
    ): String =
        execute(
            deadline,
            onCallNotPermitted = { "not-permitted" },
            onConnectFailure = { "connect-failure" },
            onTimeout = { TIMED_OUT },
            block = block,
        )

    /**
     * Class loading and first-use initialisation of Resilience4j and the scoped-value call context cost several
     * hundred ms in a cold JVM; that is not what these cases time, so one untimed call warms them first.
     */
    @BeforeEach
    fun warmUp() {
        edge(Duration.ofSeconds(5)).run(Deadline.after(Duration.ofSeconds(5), clock)) { "warm" }
        edge(Duration.ofSeconds(5)).run(Deadline.after(Duration.ofMillis(20), clock)) {
            Thread.sleep(200)
            "warm"
        }
    }

    private fun elapsedMs(since: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    // Timing subject: a real timer, so the wall clock and the MARGIN_MS cap.
    @Test
    fun `INV-AC-28249 (a) policy 5 s, deadline 300 ms, block 2 s - timeout at the deadline, block interrupted`() {
        val interrupted = CountDownLatch(1)
        val started = System.nanoTime()

        val outcome =
            edge(Duration.ofSeconds(5)).run(Deadline.after(Duration.ofMillis(300), clock)) {
                try {
                    Thread.sleep(SLOW_MS)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                "late"
            }
        val elapsed = elapsedMs(started)

        outcome shouldBe TIMED_OUT
        elapsed shouldBeLessThan 300 + MARGIN_MS
        interrupted.await(MARGIN_MS, TimeUnit.MILLISECONDS) shouldBe true
    }

    // Timing subject: a real timer, so the wall clock and the MARGIN_MS cap.
    @Test
    fun `INV-AC-28249 (b) policy 100 ms, deadline 30 s, block 2 s - the policy timeout applies`() {
        val started = System.nanoTime()

        val outcome =
            edge(Duration.ofMillis(100)).run(Deadline.after(Duration.ofSeconds(30), clock)) {
                Thread.sleep(SLOW_MS)
                "late"
            }

        outcome shouldBe TIMED_OUT
        elapsedMs(started) shouldBeLessThan 100 + MARGIN_MS
    }

    // Timing subject: only the arithmetic of deadline.remaining(clock), so the injected fixed clock.
    @Test
    fun `INV-AC-28249 (c) an already-expired deadline runs no block`() {
        val invocations = AtomicInteger()

        val outcome =
            edge(Duration.ofSeconds(5), clock = fixedClock).run(Deadline.after(Duration.ZERO, fixedClock)) {
                invocations.incrementAndGet()
                "ok"
            }

        outcome shouldBe TIMED_OUT
        invocations.get() shouldBe 0
    }

    // Timing subject: a real timer, so the wall clock and the MARGIN_MS cap.
    @Test
    fun `INV-AC-28249 (d) a block that ignores interruption does not hold the caller past the deadline`() {
        val started = System.nanoTime()

        val outcome =
            edge(Duration.ofSeconds(5)).run(Deadline.after(Duration.ofMillis(300), clock)) {
                val busyUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SLOW_MS)
                while (System.nanoTime() < busyUntil) {
                    Thread.onSpinWait()
                }
                "late"
            }

        outcome shouldBe TIMED_OUT
        elapsedMs(started) shouldBeLessThan 300 + MARGIN_MS
    }

    // Timing subject: a real timer, so the wall clock and the MARGIN_MS cap.
    @Test
    fun `INV-AC-28249 (e) idempotent edge, three attempts - the block runs once when the deadline ends the first`() {
        val invocations = AtomicInteger()
        val started = System.nanoTime()

        val outcome =
            edge(Duration.ofSeconds(5), retryAttempts = 3).run(Deadline.after(Duration.ofMillis(300), clock)) {
                invocations.incrementAndGet()
                Thread.sleep(SLOW_MS)
                "late"
            }

        outcome shouldBe TIMED_OUT
        elapsedMs(started) shouldBeLessThan 300 + MARGIN_MS
        invocations.get() shouldBe 1
    }

    // Counter-case: only deadline arithmetic is subject, so the injected fixed clock.
    @Test
    fun `INV-AC-28249 (f) a call that finishes inside both bounds returns its own value`() {
        val invocations = AtomicInteger()

        val outcome =
            edge(Duration.ofSeconds(5), clock = fixedClock).run(Deadline.after(Duration.ofMillis(300), fixedClock)) {
                invocations.incrementAndGet()
                Thread.sleep(20)
                "own-value"
            }

        outcome shouldBe "own-value"
        invocations.get() shouldBe 1
    }

    private companion object {
        const val TIMED_OUT = "timed-out"

        // Scheduling and virtual-thread hand-off of a loaded CI runner; the spec caps it at 150 ms.
        const val MARGIN_MS = 150L

        const val SLOW_MS = 2_000L
    }
}
