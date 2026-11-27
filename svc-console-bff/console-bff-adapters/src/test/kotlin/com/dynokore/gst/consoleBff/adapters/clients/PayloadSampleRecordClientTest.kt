package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleReply
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.node.ObjectNode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors

/** The shared vector of INV-AC-27783: the same literals as in the payload cases. */
internal object SampleVector {
    const val ENTITY = "ENT-0001"
    const val CHECK = "chk-0001"
    const val CHECKED_BY = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b"
    const val CONTENT = "JVBERi0xLjQK"
    const val SHA256 = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab4"
    val BYTES = "%PDF-1.4\n".toByteArray(Charsets.US_ASCII)
    val CHECKED_AT: Instant = Instant.parse("2026-03-20T03:20:00Z")
    val MISSING_WORDING = listOf("Buyer Created Tax Invoice")

    /** The members that both payload reads carry, as JSON object members. */
    private const val SHARED =
        """"checkId":"$CHECK","entityRef":"$ENTITY","scenario":"SELF_BILLING","submissionType":"TYPE_2",""" +
            """"sampleSha256":"$SHA256","result":"FAILED","textFound":true,""" +
            """"missingWording":["Buyer Created Tax Invoice"],"checkedAt":"2026-03-20T03:20:00Z",""" +
            """"checkedBy":"$CHECKED_BY","findingCode":"PAY-014""""

    const val META_CHECK = "{$SHARED}"
    const val RECORD_CHECK = """{$SHARED,"sampleContent":"$CONTENT","artefactBytes":"eyJhIjoxfQ=="}"""

    val REQUIRED =
        listOf(
            "checkId",
            "entityRef",
            "scenario",
            "submissionType",
            "sampleSha256",
            "result",
            "textFound",
            "missingWording",
            "checkedAt",
            "checkedBy",
        )

    fun body(
        check: String,
        standing: String? = """{"open":true}""",
    ): String = if (standing == null) """{"check":$check}""" else """{"check":$check,"standing":$standing}"""

    /** [json] with [member] set to the JSON text [value]; the member must exist, so no case is vacuous. */
    fun with(
        json: String,
        member: String,
        value: String,
    ): String {
        val tree = checkNotNull(OwnerEnvelope.tree(json)) as ObjectNode
        checkNotNull(tree.get(member)) { "$member is not a member" }
        tree.set(member, checkNotNull(OwnerEnvelope.tree("""{"v":$value}""")).get("v"))
        return tree.toString()
    }

    fun adding(
        json: String,
        members: String,
    ): String = json.dropLast(1) + ",$members}"

    /** Each shared member present with a wrong type or a wrong form. */
    val ILL_TYPED =
        listOf(
            "checkId" to "1",
            "entityRef" to "null",
            "scenario" to "true",
            "submissionType" to "[]",
            "sampleSha256" to "5",
            "result" to "{}",
            "textFound" to "\"true\"",
            "textFound" to "1",
            "missingWording" to "\"Buyer Created Tax Invoice\"",
            "missingWording" to "[\"a\",1]",
            "missingWording" to "[null]",
            "checkedAt" to "\"yesterday\"",
            "checkedAt" to "\"2026-03-20T03:20:00\"",
            "checkedAt" to "1774",
            "checkedBy" to "null",
            "findingCode" to "null",
            "findingCode" to "14",
        )
}

/** The refusal of the stub's envelope, as it must reach the use case. */
internal fun stubRefusal(status: Int) = OwnerRefusal(status, "SRT-$status", "T", "BLOCK", "MANUAL", "R", "D")

/** The answers other than a readable 400, 403 or 404 that a refusal case must not relay: status to body. */
internal fun notRelayed(): List<Pair<Int, String?>> =
    listOf(409 to envelope(409, "SRT-409"), 400 to "not json", 500 to envelope(500, "SRT-500")) +
        listOf(401, 422, 429, 502, 503).map { it to envelope(it, "SRT-$it") } +
        UNREADABLE_BODIES.flatMap { body -> listOf(400, 403, 404).map { it to body } }

/** A stub that answers 200 with [body] only after [delayMillis]. */
internal class DelayedStub(
    delayMillis: Long,
    body: String,
) : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = this@DelayedStub.executor
            createContext("/") { x ->
                Thread.sleep(delayMillis)
                val bytes = body.toByteArray()
                x.responseHeaders.add("Content-Type", "application/json")
                x.sendResponseHeaders(200, bytes.size.toLong())
                x.responseBody.write(bytes)
                x.close()
            }
            start()
        }
    val url = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

/** The shipped Resilience4j policy with the timeout of the PAYLOAD edge. */
internal fun payloadEdgePolicy() =
    Resilience4jResilienceRegistry(
        ResilienceProperties(
            edges = mapOf(OwningService.PAYLOAD.edge to EdgeProperties(timeout = OwningService.PAYLOAD.timeout)),
        ),
        Clock.systemUTC(),
    )

/** INV-AC-27783: PayloadSampleRecordClient over the PAYLOAD edge and a stub payload-svc. */
class PayloadSampleRecordClientTest {
    private val stub = OwnerStub()
    private val record = SampleVector.RECORD_CHECK

    @AfterEach
    fun stop() = stub.stop()

    private fun read(
        registry: ResilienceRegistry = TestEdges.passThrough,
        url: String = stub.url,
        clock: Clock = TestEdges.clock,
        entityRef: String = SampleVector.ENTITY,
        checkId: String = SampleVector.CHECK,
    ): SampleReply<SampleRecord> =
        CurrentEndUserToken.callWith("Bearer u-1") {
            PayloadSampleRecordClient(
                TestEdges.builder(),
                registry,
                clock,
                MockEnvironment().withProperty(OwningService.PAYLOAD.urlProperty, url),
            ).read(entityRef, checkId)
        }

    private fun answered(check: String): SampleReply<SampleRecord> {
        stub.answer(200, SampleVector.body(check))
        return read()
    }

    private fun value(reply: SampleReply<SampleRecord>): SampleRecord {
        assertTrue(reply is SampleReply.Ok, "$reply")
        return (reply as SampleReply.Ok).value
    }

    private fun assertUnavailable(
        reply: SampleReply<SampleRecord>,
        case: String,
    ) = assertTrue(reply is SampleReply.Unavailable, "$case: $reply")

    @Test
    @DisplayName("INV-AC-27783 record the shared vector gives the record with the 9 sample bytes")
    fun vector() {
        val got = value(answered(record))
        assertEquals(SampleVector.CHECK, got.checkId)
        assertEquals(SampleVector.ENTITY, got.entityRef)
        assertEquals("SELF_BILLING", got.scenario)
        assertEquals("TYPE_2", got.submissionType)
        assertEquals(9, got.sampleContent.size)
        assertArrayEquals(SampleVector.BYTES, got.sampleContent)
        assertEquals(SampleVector.SHA256, got.sampleSha256)
        assertEquals("FAILED", got.result)
        assertEquals(true, got.textFound)
        assertEquals(SampleVector.MISSING_WORDING, got.missingWording)
        assertEquals(SampleVector.CHECKED_AT, got.checkedAt)
        assertEquals(SampleVector.CHECKED_BY, got.checkedBy)
        assertEquals("PAY-014", got.findingCode)
    }

    @Test
    @DisplayName("INV-AC-27783 record the request is the contract GET of the byte-bearing read with the user's token")
    fun requestShape() {
        answered(record)
        val seen = stub.seen.single()
        assertEquals("GET", seen.method)
        assertEquals("/internal/v1/payload/entities/ENT-0001/rendition-sample-checks/chk-0001", seen.path)
        assertEquals("Bearer u-1", seen.authorization)
    }

    @Test
    @DisplayName("INV-AC-27783 record a missing member gives Unavailable and never a default")
    fun missingMember() {
        for (member in SampleVector.REQUIRED + "sampleContent") {
            assertUnavailable(answered(without(record, member)), "lacking $member")
        }
        for (body in listOf("{}", """{"check":null}""", """{"check":[]}""", record)) {
            stub.answer(200, body)
            assertUnavailable(read(), body)
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record an absent findingCode stays null")
    fun absentFindingCode() {
        assertNull(value(answered(without(record, "findingCode"))).findingCode)
    }

    @Test
    @DisplayName("INV-AC-27783 record an ill typed member gives Unavailable")
    fun illTyped() {
        for ((member, bad) in SampleVector.ILL_TYPED) {
            assertUnavailable(answered(SampleVector.with(record, member, bad)), "$member=$bad")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record sampleContent that is not strict base64, or a number, gives Unavailable")
    fun strictBase64() {
        val bad =
            listOf(
                "\"JVBERi0xLjQ!\"", // outside the alphabet
                "\"JVBERi0x_jQK\"", // the URL-safe alphabet
                "\"JVBERi0x LjQK\"",
                "\"JVBERi0x\\nLjQK\"",
                "\"JVBERi0xLjQK \"",
                "\"JVBERi0xLjQ\"", // the padding is missing
                "\"JVBERi0xLjR=\"", // non-zero pad bits
                "\"JVBERi0xLjQK=\"",
                "12345678",
                "null",
                "[\"JVBERi0xLjQK\"]",
            )
        for (content in bad) {
            assertUnavailable(answered(SampleVector.with(record, "sampleContent", content)), content)
        }
        // the canonical padded form of 8 bytes is read
        val padded = value(answered(SampleVector.with(record, "sampleContent", "\"JVBERi0xLjQ=\"")))
        assertEquals(8, padded.sampleContent.size)
    }

    @Test
    @DisplayName("INV-AC-27783 record artefactBytes is never read: absent or unusable, the record is the same")
    fun artefactBytesIgnored() {
        val answers =
            listOf(without(record, "artefactBytes")) +
                listOf("null", "7", "\"not base64 !\"", "{}").map {
                    SampleVector.with(record, "artefactBytes", it)
                }
        for (check in answers) {
            assertArrayEquals(SampleVector.BYTES, value(answered(check)).sampleContent, check)
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record unknown extra members are accepted and standing is not needed")
    fun extraMembers() {
        val extra = SampleVector.adding(record, """"surprise":{"a":1}""")
        assertArrayEquals(SampleVector.BYTES, value(answered(extra)).sampleContent)
        stub.answer(200, SampleVector.adding(SampleVector.body(record, standing = null), """"more":1"""))
        assertArrayEquals(SampleVector.BYTES, value(read()).sampleContent)
    }

    @Test
    @DisplayName("INV-AC-27783 record 400, 403 and 404 with a readable envelope are relayed unchanged")
    fun refusalsRelayed() {
        val expected =
            mapOf(
                400 to SampleReply.Invalid(stubRefusal(400)),
                403 to SampleReply.Forbidden(stubRefusal(403)),
                404 to SampleReply.NotFound(stubRefusal(404)),
            )
        for ((status, reply) in expected) {
            stub.answer(status, envelope(status, "SRT-$status"))
            assertEquals(reply, read(), "status $status")
            assertEquals(1, stub.seen.size, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record a 409, an unreadable 400, a 500 and every other failure give Unavailable")
    fun otherFailures() {
        for ((status, body) in notRelayed()) {
            stub.answer(status, body)
            assertUnavailable(read(), "$status $body")
            assertEquals(1, stub.seen.size, "no retry for $status")
        }
        for (status in listOf(201, 202, 204, 206)) {
            stub.answer(status, if (status == 204) null else SampleVector.body(record))
            assertUnavailable(read(), "status $status")
        }
        for (body in UNREADABLE_BODIES + "${SampleVector.body(record)} trailing") {
            stub.answer(200, body)
            assertUnavailable(read(), "200 $body")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record a delayed answer beyond the 2000 ms edge timeout gives Unavailable")
    fun delayed() {
        payloadEdgePolicy().use { registry ->
            DelayedStub(OwningService.PAYLOAD.timeout.toMillis() + 1500, SampleVector.body(record)).use { slow ->
                val reply = read(registry, slow.url, Clock.systemUTC())
                assertEquals(SampleReply.Unavailable("console-bff->payload-svc: timed out"), reply)
            }
            // the same policy reads an answer inside the timeout
            DelayedStub(0, SampleVector.body(record)).use { quick ->
                value(read(registry, quick.url, Clock.systemUTC()))
            }
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record refused connection, open breaker and a dead owner give Unavailable")
    fun noAnswer() {
        stub.answer(200, SampleVector.body(record))
        for (registry in listOf(TestEdges.connectRefused, TestEdges.timedOut, TestEdges.breakerOpen)) {
            assertUnavailable(read(registry), "edge")
        }
        assertEquals(0, stub.seen.size)
        stub.stop()
        assertUnavailable(read(), "dead owner")
    }

    @Test
    @DisplayName("INV-AC-27783 record a blank or dot-segment entityRef or checkId is never sent")
    fun pathSegments() {
        stub.answer(200, SampleVector.body(record))
        for (bad in listOf("", " ", ".", "..")) {
            assertUnavailable(read(entityRef = bad), "entityRef '$bad'")
            assertUnavailable(read(checkId = bad), "checkId '$bad'")
        }
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27783 record an answer with a malformed Content-Type or Content-Length is Unavailable")
    fun malformedHeaders() {
        val raw = RawStub()
        try {
            for (contentLength in MALFORMED_CONTENT_LENGTHS) {
                raw.answer(200, contentLength, SampleVector.body(record))
                assertUnavailable(read(url = raw.url), contentLength)
                assertEquals(1, raw.requests.get(), contentLength)
            }
        } finally {
            raw.stop()
        }
        val typed = PageStub()
        try {
            for (contentType in MALFORMED_CONTENT_TYPES) {
                typed.answer(200, SampleVector.body(record), contentType)
                assertUnavailable(read(url = typed.url), contentType)
                assertEquals(1, typed.seen.size, contentType)
            }
        } finally {
            typed.stop()
        }
    }

    @Test
    @DisplayName("INV-AC-27783 record a missing, blank or unresolved console.payload-svc-url stops the start")
    fun baseUrlRequired() {
        val property = OwningService.PAYLOAD.urlProperty
        val client = PayloadSampleRecordClient::class.java
        assertNull(startFailure(client, property to stub.url))
        assertTrue(namesProperty(startFailure(client, "console.evidence-svc-url" to stub.url), property), "absent")
        for (bad in BAD_BASE_URLS) {
            val failure = startFailure(client, property to bad)
            val named = namesProperty(failure, property) || namesProperty(failure, "CONSOLE_UNSET_IN_TEST")
            assertTrue(named, "'$bad' $failure")
        }
    }
}
