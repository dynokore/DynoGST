package com.dynokore.gst.payload.bootstrap

import com.dynokore.platform.serviceruntime.identity.FlowContextTestSupport
import com.dynokore.platform.serviceruntime.identity.FlowEntity
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/**
 * INV-AC-2201 (INV-REQ-1203, INV-NOTE-3 FR-AH.7): a register that answers in 2 s, no cached answer, a request
 * deadline of 300 ms: the wired `checkPreIssuance` answers 200 with the verdict WARN and the flag
 * REGISTER_UNAVAILABLE inside the budget, not an error. The service is the real wiring (controller, use case,
 * register adapter, service-runtime filters) over a stub of masterdata-svc `getRegisterAndRates`; the deadline
 * travels in the API-29 header `X-Deadline-Ms`.
 */
class PreIssuanceBudgetTest : OverrideWiringBase() {
    /** A masterdata-svc stub that answers REGISTERED after [delayMs]; it never answers faster than that. */
    private class SlowMasterdata(
        @Volatile var delayMs: Long,
    ) : AutoCloseable {
        private val pool = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                executor = pool
                createContext("/") { exchange ->
                    exchange.requestBody.readAllBytes()
                    runCatching { Thread.sleep(delayMs) }
                    val bytes = """{"register":{"status":"REGISTERED","asOf":"2026-03-20"}}""".toByteArray()
                    runCatching {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                    exchange.close()
                }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private val path = "/internal/v1/payload/pre-issuance-checks"

    private fun draft(gstn: String? = "M90054321K"): String =
        listOfNotNull(
            "\"entityRef\":\"E-1\"",
            "\"currency\":\"SGD\"",
            "\"totalIncludingTax\":\"109.00\"",
            "\"submissionType\":\"TYPE_1A\"",
            gstn?.let { "\"supplierGstn\":\"$it\"" },
            "\"invoiceDate\":\"2026-03-20\"",
            "\"gstAmount\":\"9.00\"",
        ).joinToString(",", "{", "}")

    /** The wired service as [withService] serves it, with a MockMvc of the same filters carrying `X-Deadline-Ms`. */
    private fun <T> served(
        delayMs: Long,
        block: (MockMvc, SlowMasterdata) -> T,
    ): T =
        SlowMasterdata(delayMs).use { md ->
            AccessStub().use { access ->
                withService(localReplica(), access, masterdataUrl = md.url) { service ->
                    val context = checkNotNull(service.context)
                    val filters =
                        context
                            .getBeansOfType(FilterRegistrationBean::class.java)
                            .values
                            .sortedBy { it.order }
                            .map { it.filter as Filter }
                    val mvc =
                        MockMvcBuilders
                            .webAppContextSetup(context as WebApplicationContext)
                            .also { it.addFilters<DefaultMockMvcBuilder>(*filters.toTypedArray()) }
                            .build()
                    block(mvc, md)
                }
            }
        }

    private class Timed(
        val status: Int,
        val body: String,
        val millis: Long,
    )

    private fun MockMvc.check(
        body: String,
        deadlineMs: Long?,
    ): Timed {
        val request =
            MockMvcRequestBuilders
                .post(path)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Workload-Token", "W:subject-ingestion-svc")
                .header("Authorization", "Bearer U-u-1")
                .header(
                    "X-Flow-Context",
                    FlowContextTestSupport.contextFor(
                        tenant = EndUserDoubles.tenantA.toString(),
                        entity = FlowEntity.entity("E-001"),
                        principalKind = "HUMAN",
                        callerService = "ingestion-svc",
                        issuer = flowContextIssuer,
                    ),
                ).contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .apply { deadlineMs?.let { header(Deadline.HEADER_NAME, it.toString()) } }
        val started = System.nanoTime()
        val response = perform(request).andReturn().response
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        return Timed(response.status, response.contentAsString, millis)
    }

    /**
     * One call to warm the class loading and the connection path, so the timed call measures the budget only. It uses
     * another supplier GSTN, so it leaves no cached register answer for the timed call.
     */
    private fun MockMvc.warmUp(md: SlowMasterdata) {
        val delay = md.delayMs
        md.delayMs = 0
        check(draft(WARM_GSTN), BUDGET_MS)
        md.delayMs = delay
    }

    @Test
    fun `INV-AC-2201 a register answering in 2 s gives 200 WARN with REGISTER_UNAVAILABLE within the 300 ms budget`() {
        served(SLOW_REGISTER_MS) { mvc, md ->
            mvc.warmUp(md)
            val answer = mvc.check(draft(), BUDGET_MS)
            answer.status shouldBe 200
            answer.body shouldContain "\"verdict\":\"WARN\""
            flagsOf(answer.body) shouldBe listOf("REGISTER_UNAVAILABLE")
            answer.millis shouldBeLessThan BUDGET_MS + MARGIN_MS
        }
    }

    @Test
    fun `INV-AC-2201 a register answering in 50 ms gives the decided verdict with no flags, so WARN is not constant`() {
        served(FAST_REGISTER_MS) { mvc, md ->
            mvc.warmUp(md)
            val answer = mvc.check(draft(), BUDGET_MS)
            answer.status shouldBe 200
            answer.body shouldContain "\"verdict\":\"PASS\""
            answer.body shouldNotContain "flags"
            answer.body shouldNotContain "REGISTER_UNAVAILABLE"
        }
    }

    @Test
    fun `INV-AC-2201 a draft without supplierGstn is 400 and never the WARN of the budget case`() {
        served(SLOW_REGISTER_MS) { mvc, _ ->
            val answer = mvc.check(draft(gstn = null), BUDGET_MS)
            answer.status shouldBe 400
            answer.body shouldContain "SRT-001"
            answer.body shouldNotContain "REGISTER_UNAVAILABLE"
        }
    }

    @Test
    fun `INV-AC-2201 the timing margin is at most 100 ms`() {
        (MARGIN_MS <= MAX_MARGIN_MS) shouldBe true
    }

    /** The members of the `flags` array of the answer, by whole-list comparison in the caller. */
    private fun flagsOf(body: String): List<String> {
        val array = Regex("\"flags\":\\[([^\\]]*)]").find(body)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(array).map { it.groupValues[1] }.toList()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** The request deadline of the criterion: the pre-issuance budget. */
        const val BUDGET_MS = 300L
        const val WARM_GSTN = "M90054322L"

        /** The register of the criterion answers in 2 s. */
        const val SLOW_REGISTER_MS = 2000L

        /** An answer well inside the budget, for the counter-case. */
        const val FAST_REGISTER_MS = 50L

        /** The most the margin may be (the budget plus this is the ceiling of the time assertion). */
        const val MAX_MARGIN_MS = 100L

        /**
         * Allowance over the 300 ms budget for work that is not the register wait: servlet filters, JSON in and out and
         * thread hand-off between the use-case thread and the edge's worker, on a shared CI runner.
         */
        const val MARGIN_MS = 100L
    }
}
