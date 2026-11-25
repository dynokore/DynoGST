package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.clients.MasterdataRegisterStatusSource
import com.dynokore.gst.payload.application.preissuance.CheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * INV-AC-26308: the real service over HTTP with a stub of masterdata-svc `getRegisterAndRates`. The register client and
 * the use case are the beans of [PreIssuanceConfiguration].
 */
class PreIssuanceWiringTest : OverrideWiringBase() {
    /** A masterdata-svc double: records the query of every request and answers as configured. */
    private class MasterdataStub : AutoCloseable {
        @Volatile var status = 200

        @Volatile var body = answer("NOT_REGISTERED")
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.readAllBytes()
                    requests +=
                        exchange.requestURI.rawQuery
                            .orEmpty()
                            .split('&')
                            .filter { it.isNotEmpty() }
                            .associate {
                                val (k, v) = it.split('=', limit = 2)
                                k to URLDecoder.decode(v, StandardCharsets.UTF_8)
                            }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                    exchange.close()
                }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        fun answers(status: String) {
            this.status = 200
            body = answer(status)
        }

        override fun close() = server.stop(0)

        companion object {
            fun answer(status: String) = """{"register":{"status":"$status","asOf":"2026-03-20"}}"""
        }
    }

    private fun draft(
        type: String? = "TYPE_1A",
        gstn: String? = "M90054321K",
        date: String? = "2026-03-20",
        amount: String? = "9.00",
    ): String =
        listOfNotNull(
            "\"entityRef\":\"E-001\"",
            "\"currency\":\"SGD\"",
            "\"totalIncludingTax\":\"109.00\"",
            type?.let { "\"submissionType\":\"$it\"" },
            gstn?.let { "\"supplierGstn\":\"$it\"" },
            date?.let { "\"invoiceDate\":\"$it\"" },
            amount?.let { "\"gstAmount\":\"$it\"" },
        ).joinToString(",", "{", "}")

    private val path = "/internal/v1/payload/pre-issuance-checks"

    private fun <T> served(block: (ServedService, MasterdataStub) -> T): T =
        MasterdataStub().use { md ->
            AccessStub().use { access ->
                withService(
                    localReplica("u-1" to "transaction.ingest"),
                    access,
                    masterdataUrl = md.url,
                ) { service -> block(service, md) }
            }
        }

    private fun ServedService.check(body: String): Answer =
        send("POST", path, "W:subject-ingestion-svc", "Bearer U-u-1", body)

    @Test
    fun `INV-AC-26308 NOT_REGISTERED is BLOCK for TYPE_1A and WARN for TYPE_2 and the stub gets the draft facts`() {
        served { service, md ->
            val rows = service.state()
            val block = service.check(draft())
            block.status shouldBe 200
            block.body shouldContain "\"verdict\":\"BLOCK\""
            block.body shouldContain "\"code\":\"PAY-011\""
            block.body shouldContain "\"field\":\"supplierGstn\""
            block.body shouldContain "\"severity\":\"BLOCK\""
            block.body shouldContain "\"overridable\":true"
            val warn = service.check(draft(type = "TYPE_2"))
            warn.status shouldBe 200
            warn.body shouldContain "\"verdict\":\"WARN\""
            warn.body shouldContain "\"severity\":\"WARN\""
            md.requests.size shouldBe 2
            md.requests.forEach {
                it["counterpartyUen"] shouldBe "M90054321K"
                it["transactionDate"] shouldBe "2026-03-20"
                it["entityRef"] shouldBe "E-001"
                it["currency"] shouldBe "SGD"
            }
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26308 REGISTERED and a zero amount are PASS with no reasons and no flags`() {
        served { service, md ->
            val rows = service.state()
            md.answers("REGISTERED")
            val registered = service.check(draft())
            registered.status shouldBe 200
            registered.body shouldContain "\"verdict\":\"PASS\""
            registered.body shouldContain "\"reasons\":[]"
            registered.body shouldBe registered.body.takeUnless { it.contains("flags") }
            md.answers("NOT_REGISTERED")
            val zero = service.check(draft(amount = "0.00"))
            zero.status shouldBe 200
            zero.body shouldContain "\"verdict\":\"PASS\""
            zero.body shouldContain "\"reasons\":[]"
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26308 an unavailable register, an unlisted status, a 503 and no answer are WARN with the flag`() {
        served { service, md ->
            val rows = service.state()
            val unwanted = listOf("REGISTER_UNAVAILABLE", "SUSPENDED")
            unwanted.forEach { status ->
                md.answers(status)
                val answer = service.check(draft())
                answer.status shouldBe 200
                answer.body shouldContain "\"verdict\":\"WARN\""
                answer.body shouldContain "\"reasons\":[]"
                answer.body shouldContain "\"flags\":[\"REGISTER_UNAVAILABLE\"]"
            }
            md.status = 503
            md.body = ""
            val fault = service.check(draft())
            fault.status shouldBe 200
            fault.body shouldContain "\"flags\":[\"REGISTER_UNAVAILABLE\"]"
            fault.body shouldContain "\"verdict\":\"WARN\""
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26308 no answer from masterdata is WARN with the flag`() {
        MasterdataStub().use { md ->
            val dead = md.url
            md.close()
            AccessStub().use { access ->
                withService(localReplica("u-1" to "transaction.ingest"), access, masterdataUrl = dead) { service ->
                    val answer = service.check(draft())
                    answer.status shouldBe 200
                    answer.body shouldContain "\"verdict\":\"WARN\""
                    answer.body shouldContain "\"flags\":[\"REGISTER_UNAVAILABLE\"]"
                }
            }
        }
    }

    @Test
    fun `INV-AC-26308 each left-out field is 400 SRT-001 with no lookup and no row written`() {
        served { service, md ->
            val rows = service.state()
            listOf(
                draft(type = null),
                draft(gstn = null),
                draft(date = null),
                draft(amount = null),
            ).forEach { body ->
                val answer = service.check(body)
                answer.status shouldBe 400
                answer.body shouldContain "SRT-001"
            }
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26308 a blank supplierGstn, entityRef or currency is 400 SRT-001 with no lookup and never 500`() {
        served { service, md ->
            val rows = service.state()
            listOf("supplierGstn", "entityRef", "currency").forEach { key ->
                val body = draft().replace(Regex("\"$key\":\"[^\"]*\""), "\"$key\":\"\"")
                val answer = service.check(body)
                answer.status shouldBe 400
                answer.body shouldContain "SRT-001"
            }
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26308 a gstAmount of 9_001 is refused by the use case as 400 SRT-001 with no verdict and no lookup`() {
        served { service, md ->
            val rows = service.state()
            listOf("9.001", "-", "abc").forEach { amount ->
                val answer = service.check(draft(amount = amount))
                answer.status shouldBe 400
                answer.body shouldContain "SRT-001"
                answer.body.contains("verdict") shouldBe false
            }
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    private val property = "payload.masterdata-svc-url"

    private val ingest = "transaction.ingest"

    private fun ServedService.checkAs(
        authorization: String,
        body: String = draft(),
    ): Answer = send("POST", path, "W:subject-ingestion-svc", authorization, body)

    private fun servedWith(
        replica: AuthorityReplica,
        block: (ServedService, MasterdataStub) -> Unit,
    ) {
        MasterdataStub().use { md ->
            AccessStub().use { access ->
                withService(replica, access, masterdataUrl = md.url) { service -> block(service, md) }
            }
        }
    }

    @Test
    fun `INV-AC-27312 the holder of the key gets 200 and the verdict and no row is written`() {
        servedWith(localReplica("u-1" to ingest)) { service, md ->
            val rows = service.state()
            val answer = service.checkAs("Bearer U-u-1")
            answer.status shouldBe 200
            answer.body shouldContain "\"verdict\":\"BLOCK\""
            md.requests.size shouldBe 1
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-27312 a caller whose roles hold no cell of the key gets 403 SRT-004 and the stub gets 0 requests`() {
        servedWith(localReplica("u-1" to "validation.override")) { service, md ->
            val rows = service.state()
            val answer = service.checkAs("Bearer U-u-1")
            answer.status shouldBe 403
            answer.body shouldContain "SRT-004"
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-27312 a request with no principal is 401 and the stub gets 0 requests`() {
        servedWith(localReplica("u-1" to ingest)) { service, md ->
            val rows = service.state()
            service.send("POST", path, "W:subject-ingestion-svc", "", draft()).status shouldBe 401
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-27312 a DEGRADED decision is 403 and the stub gets 0 requests`() {
        // no snapshot and no fallback: the replica cannot decide, the source is DEGRADED
        servedWith(AuthorityReplica(Clock.systemUTC(), roleMap)) { service, md ->
            val rows = service.state()
            val answer = service.checkAs("Bearer U-u-1")
            answer.status shouldBe 403
            answer.body shouldContain "SRT-004"
            md.requests.size shouldBe 0
            service.state() shouldBe rows
        }
    }

    @Test
    fun `INV-AC-26694 W1 with the property set the context holds exactly one CheckPreIssuance bean`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offlineWithMasterdataUrl(dir, "http://masterdata.invalid").run { ctx ->
            ctx.startupFailure shouldBe null
            ctx.getBeansOfType(CheckPreIssuance::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-26694 W2 the one RegisterStatusSource bean is the masterdata adapter`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offlineWithMasterdataUrl(dir, "http://masterdata.invalid").run { ctx ->
            val beans = ctx.getBeansOfType(RegisterStatusSource::class.java)
            beans.size shouldBe 1
            beans.values
                .single()
                .javaClass.name shouldBe MasterdataRegisterStatusSource::class.java.name
        }
    }

    @Test
    fun `INV-AC-26694 W3 with the property missing the context fails to start and names it`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offlineWithMasterdataUrl(dir, null).run { ctx ->
            val failure = checkNotNull(ctx.startupFailure)
            generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains(property) } shouldBe
                true
        }
    }

    @Test
    fun `INV-AC-26694 W4 with the property empty or blank the context fails to start`(
        @TempDir dir: Path,
    ) {
        listOf("", "   ").forEach { blank ->
            PayloadContext.offlineWithMasterdataUrl(dir, blank).run { ctx ->
                (ctx.startupFailure != null) shouldBe true
            }
        }
    }
}
