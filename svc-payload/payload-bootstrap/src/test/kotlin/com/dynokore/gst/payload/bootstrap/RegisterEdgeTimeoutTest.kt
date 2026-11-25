package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.clients.MasterdataRegisterStatusSource
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

/**
 * INV-AC-28915: the shipped configuration bounds the register edge by the published 1000 ms. The served requests carry
 * no `X-Deadline-Ms` header, so each has the default deadline budget and only the edge's own timeout can end the
 * register leg. This test sets no `service-runtime.resilience` property: what it reads is the shipped application.yaml.
 */
class RegisterEdgeTimeoutTest : OverrideWiringBase() {
    /** A masterdata-svc double that answers REGISTERED after [delayMillis], each request on its own thread. */
    private class SlowRegisterStub : AutoCloseable {
        @Volatile var delayMillis = FAST_MILLIS

        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also {
                it.executor = executor
                it.createContext("/") { exchange ->
                    exchange.requestBody.readAllBytes()
                    Thread.sleep(delayMillis)
                    val bytes = """{"register":{"status":"REGISTERED","asOf":"2026-03-20"}}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.close()
                }
                it.start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private val mapper = JsonMapper.builder().build()

    private fun draft(gstn: String): String =
        """{"entityRef":"E-001","currency":"SGD","totalIncludingTax":"109.00","submissionType":"TYPE_1A",""" +
            """"supplierGstn":"$gstn","invoiceDate":"2026-03-20","gstAmount":"9.00"}"""

    private fun ServedService.checkPreIssuance(gstn: String): Answer =
        send(
            "POST",
            "/internal/v1/payload/pre-issuance-checks",
            "W:subject-ingestion-svc",
            "Bearer U-u-1",
            draft(gstn),
        )

    private fun served(block: (ServedService, SlowRegisterStub) -> Unit) {
        SlowRegisterStub().use { register ->
            AccessStub().use { access ->
                withService(
                    localReplica("u-1" to "transaction.ingest"),
                    access,
                    masterdataUrl = register.url,
                ) { service -> block(service, register) }
            }
        }
    }

    private fun verdictOf(answer: Answer): String? = mapper.readTree(answer.body).get("verdict")?.asString()

    /**
     * The first requests pay class loading and connection set-up, which can exceed the edge timeout: repeat with a fast
     * stub and another Supplier GSTN until one is PASS. Fewer attempts than the breaker's window of 20 calls.
     */
    private fun ServedService.warmUp(register: SlowRegisterStub) {
        register.delayMillis = FAST_MILLIS
        val seen = mutableListOf<Answer>()
        repeat(WARM_UP_ATTEMPTS) {
            val answer = checkPreIssuance("M90000000W")
            if (answer.status == 200 && verdictOf(answer) == "PASS") return
            seen += answer
        }
        throw AssertionError("INV-AC-28915 warm-up never answered PASS: $seen")
    }

    private data class Timed(
        val answer: Answer,
        val elapsedMillis: Long,
    ) {
        val clue = "status=${answer.status} elapsedMs=$elapsedMillis body=${answer.body}"
    }

    private fun ServedService.timedCheck(): Timed {
        val start = System.nanoTime()
        val answer = checkPreIssuance("M90054321K")
        return Timed(answer, Duration.ofNanos(System.nanoTime() - start).toMillis())
    }

    @Test
    fun `INV-AC-28915 a the bound register edge has the 1000 ms contract timeout and no retry`() {
        served { service, _ ->
            val edges = checkNotNull(service.context).getBean(ResilienceProperties::class.java).edges
            withClue("bound edges: ${edges.keys}") {
                edges.keys.any { it == MasterdataRegisterStatusSource.EDGE } shouldBe true
            }
            val row = edges.getValue(MasterdataRegisterStatusSource.EDGE)
            row.timeout shouldBe MasterdataRegisterStatusSource.EDGE_TIMEOUT
            row.timeout shouldBe Duration.ofMillis(CONTRACT_TIMEOUT_MILLIS)
            row.retry.shouldBeNull()
        }
    }

    @Test
    fun `INV-AC-28915 b a register answering in 2 s is cut at 1000 ms and the verdict is WARN with the flag`() {
        served { service, register ->
            service.warmUp(register)
            register.delayMillis = SLOW_MILLIS
            val timed = service.timedCheck()
            withClue(timed.clue) {
                timed.answer.status shouldBe 200
                val json = mapper.readTree(timed.answer.body)
                json.get("verdict")?.asString() shouldBe "WARN"
                json.get("flags")?.toList()?.map { it.asString() } shouldBe listOf("REGISTER_UNAVAILABLE")
                timed.elapsedMillis shouldBeGreaterThanOrEqual CONTRACT_TIMEOUT_MILLIS
                timed.elapsedMillis shouldBeLessThan CONTRACT_TIMEOUT_MILLIS + MARGIN_MILLIS
            }
        }
    }

    @Test
    fun `INV-AC-28915 c a register answering in 50 ms gives PASS with no flags member`() {
        served { service, register ->
            service.warmUp(register)
            register.delayMillis = FAST_MILLIS
            val timed = service.timedCheck()
            withClue(timed.clue) {
                timed.answer.status shouldBe 200
                val json = mapper.readTree(timed.answer.body)
                json.get("verdict")?.asString() shouldBe "PASS"
                json.has("flags") shouldBe false
            }
        }
    }

    private companion object {
        const val CONTRACT_TIMEOUT_MILLIS = 1000L

        /**
         * The room above the timeout, the upper bound of the criterion (before 1500 ms). It covers what the warmed
         * request does around the register leg (filters, authority decision, JSON, timer wake-up) and scheduling
         * jitter on a shared runner. It ends 500 ms before the stub's 2000 ms answer, so a call that waited for the
         * register (as under the 5 s default) can never fit inside it.
         */
        const val MARGIN_MILLIS = 500L

        const val SLOW_MILLIS = 2000L
        const val FAST_MILLIS = 50L
        const val WARM_UP_ATTEMPTS = 10
    }
}
