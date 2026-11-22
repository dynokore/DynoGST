package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** One request a [HeaderStub] received, with the headers by lower-case name. */
internal class HeaderRequest(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String>,
)

/** A stub owning service like [OwnerStub] that also records the request headers. */
internal class HeaderStub {
    @Volatile var status = 200

    @Volatile var body: String? = null
    val seen = CopyOnWriteArrayList<HeaderRequest>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { x ->
                seen +=
                    HeaderRequest(
                        x.requestMethod,
                        x.requestURI.path,
                        String(x.requestBody.readAllBytes()),
                        x.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                    )
                val bytes = body?.toByteArray()
                if (bytes != null) x.responseHeaders.add("Content-Type", "application/json")
                x.sendResponseHeaders(status, bytes?.size?.toLong() ?: -1)
                bytes?.let { x.responseBody.write(it) }
                x.close()
            }
            start()
        }
    val url = "http://127.0.0.1:${server.address.port}"

    fun answer(
        status: Int,
        body: String?,
    ) {
        this.status = status
        this.body = body
        seen.clear()
    }

    fun stop() = server.stop(0)
}

/** INV-AC-27140: EvidenceAnnotationClient over the evidence edge and a stub evidence-svc. */
class EvidenceAnnotationClientTest {
    private val stub = HeaderStub()
    private val case = "22222222-2222-2222-2222-222222222222"
    private val key = "33333333-3333-3333-3333-333333333333"
    private val annotation = """{"annotationId":"44444444-4444-4444-4444-444444444444","sequence":1}"""

    @AfterEach
    fun stop() = stub.stop()

    private fun client(registry: ResilienceRegistry = TestEdges.passThrough) =
        EvidenceAnnotationClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.EVIDENCE.urlProperty, stub.url),
        )

    private fun append(
        registry: ResilienceRegistry = TestEdges.passThrough,
        caseId: String = case,
        idempotencyKey: String = key,
    ) = CurrentEndUserToken.callWith("Bearer u-1") {
        client(registry).append(caseId, idempotencyKey, "ack-1", "legalName", "wrong spelling")
    }

    @Test
    @DisplayName("INV-AC-27140 annotation 201 and 200 give Ok")
    fun created() {
        stub.answer(201, annotation)
        assertEquals(OwnerReply.Ok(Unit), append())
        stub.answer(200, annotation)
        assertEquals(OwnerReply.Ok(Unit), append())
    }

    @Test
    @DisplayName("INV-AC-27140 annotation request carries Idempotency-Key and ackId, correctedField, reason only")
    fun requestShape() {
        stub.answer(201, annotation)
        append()
        val seen = stub.seen.single()
        assertEquals("POST", seen.method)
        assertEquals("/internal/v1/evidence/cases/$case/annotations", seen.path)
        assertEquals(key, seen.headers["idempotency-key"])
        val body = OwnerEnvelope.tree(seen.body)!!
        assertEquals(setOf("ackId", "correctedField", "reason"), body.propertyNames().toSet())
        assertEquals("ack-1", OwnerEnvelope.text(body, "ackId"))
        assertEquals("legalName", OwnerEnvelope.text(body, "correctedField"))
        assertEquals("wrong spelling", OwnerEnvelope.text(body, "reason"))
        assertFalse(seen.body.contains("correctedValue"))
        assertFalse(seen.body.contains("previousValue"))
    }

    @Test
    @DisplayName("INV-AC-27140 annotation 404 and the owner's problem body are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in REFUSALS) {
            stub.answer(status, envelope(status, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D")),
                append(),
                "status $status",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-27140 annotation 5xx is Unavailable, never Refused")
    fun serverError() {
        for (status in listOf(500, 502, 503)) {
            stub.answer(status, envelope(status, "SRT-500"))
            assertTrue(append() is OwnerReply.Unavailable, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27140 annotation refused connection, open breaker and timeout are Unavailable")
    fun connectionFailure() {
        stub.answer(201, annotation)
        assertTrue(append(TestEdges.connectRefused) is OwnerReply.Unavailable)
        assertTrue(append(TestEdges.breakerOpen) is OwnerReply.Unavailable)
        assertTrue(append(TestEdges.timedOut) is OwnerReply.Unavailable)
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27140 annotation unreadable success and unreadable problem bodies are Unavailable")
    fun unreadable() {
        for (body in UNREADABLE_BODIES) {
            stub.answer(201, body)
            assertTrue(append() is OwnerReply.Unavailable, "201 $body")
            stub.answer(200, body)
            assertTrue(append() is OwnerReply.Unavailable, "200 $body")
            stub.answer(422, body)
            assertTrue(append() is OwnerReply.Unavailable, "422 $body")
        }
        stub.answer(204, null)
        assertTrue(append() is OwnerReply.Unavailable)
    }

    @Test
    @DisplayName("INV-AC-27140 annotation case id or key that is not a UUID is Unavailable without a call")
    fun invalidIdentifiers() {
        stub.answer(201, annotation)
        for (bad in listOf("", "not-a-uuid", "1-1-1-1-1", "../x", "$case/x")) {
            assertTrue(append(caseId = bad) is OwnerReply.Unavailable, "case $bad")
            assertTrue(append(idempotencyKey = bad) is OwnerReply.Unavailable, "key $bad")
        }
        assertEquals(0, stub.seen.size)
    }
}
