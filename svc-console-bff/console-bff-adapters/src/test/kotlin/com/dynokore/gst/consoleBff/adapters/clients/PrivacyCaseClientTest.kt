package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** INV-AC-27140: PrivacyCaseClient over the evidence edge and a stub evidence-svc. */
class PrivacyCaseClientTest {
    private val stub = OwnerStub()
    private val case = "22222222-2222-2222-2222-222222222222"

    @AfterEach
    fun stop() = stub.stop()

    private fun client(registry: ResilienceRegistry = TestEdges.passThrough) =
        PrivacyCaseClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.EVIDENCE.urlProperty, stub.url),
        )

    private fun status(
        registry: ResilienceRegistry = TestEdges.passThrough,
        caseId: String = case,
    ) = CurrentEndUserToken.callWith("Bearer u-1") { client(registry).status(caseId) }

    private fun caseBody(status: String) =
        """{"caseId":"$case","subjectRef":"s-1","status":"$status","openedAt":"2026-11-01T00:00:00Z"}"""

    @Test
    @DisplayName("INV-AC-27140 case 200 gives Ok with the status text")
    fun ok() {
        for (s in listOf("OPEN", "EXPORT_READY", "CLOSED")) {
            stub.answer(200, caseBody(s))
            assertEquals(OwnerReply.Ok<String?>(s), status())
        }
        val seen = stub.seen.single()
        assertEquals("GET", seen.method)
        assertEquals("/internal/v1/evidence/cases/$case", seen.path)
    }

    @Test
    @DisplayName("INV-AC-27140 case the owner's problem body is relayed unchanged, except 404 SRT-005")
    fun refusalsRelayed() {
        for ((status, code) in REFUSALS.filter { it != 404 to "SRT-005" }) {
            stub.answer(status, envelope(status, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D")),
                status(),
                "status $status",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case 404 with problem code SRT-005 is Ok(null)")
    fun unknownCase() {
        stub.answer(404, envelope(404, "SRT-005"))
        assertEquals(OwnerReply.Ok<String?>(null), status())
    }

    @Test
    @DisplayName("INV-AC-27140 case 404 with each unreadable body is Unavailable")
    fun notFoundWithoutEnvelope() {
        for (body in UNREADABLE_BODIES) {
            stub.answer(404, body)
            assertTrue(status() is OwnerReply.Unavailable, "404 $body")
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case 404 with look-alike codes is Refused unchanged")
    fun lookAlikeCodes() {
        for (code in listOf("SRT-0050", "SRT-004", "XXX-005")) {
            stub.answer(404, envelope(404, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(404, code, "T", "BLOCK", "MANUAL", "R", "D")),
                status(),
                code,
            )
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case SRT-005 with another status is Refused")
    fun notFoundCodeOtherStatus() {
        for (st in listOf(400, 403, 409, 422)) {
            stub.answer(st, envelope(st, "SRT-005"))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(st, "SRT-005", "T", "BLOCK", "MANUAL", "R", "D")),
                status(),
                "status $st",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case 5xx is Unavailable, never Refused")
    fun serverError() {
        for (code in listOf(500, 502, 503)) {
            stub.answer(code, envelope(code, "SRT-500"))
            assertTrue(status() is OwnerReply.Unavailable, "status $code")
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case refused connection, open breaker and timeout are Unavailable")
    fun connectionFailure() {
        stub.answer(200, caseBody("OPEN"))
        assertTrue(status(TestEdges.connectRefused) is OwnerReply.Unavailable)
        assertTrue(status(TestEdges.breakerOpen) is OwnerReply.Unavailable)
        assertTrue(status(TestEdges.timedOut) is OwnerReply.Unavailable)
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27140 case unreadable success and problem bodies are Unavailable")
    fun unreadable() {
        for (body in UNREADABLE_BODIES + """{"caseId":"x"}""" + """{"status":3}""") {
            stub.answer(200, body)
            assertTrue(status() is OwnerReply.Unavailable, "200 $body")
            stub.answer(422, body)
            assertTrue(status() is OwnerReply.Unavailable, "422 $body")
        }
    }

    @Test
    @DisplayName("INV-AC-27140 case id that is not a UUID is Unavailable without a call")
    fun invalidCaseId() {
        stub.answer(200, caseBody("OPEN"))
        for (bad in listOf("", "not-a-uuid", "1-1-1-1-1", "../x", "$case/annotations")) {
            assertTrue(status(caseId = bad) is OwnerReply.Unavailable, bad)
        }
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27140 a blank console.evidence-svc-url fails the start")
    fun blankEvidenceUrl() {
        for (blank in listOf("", " ")) {
            assertThrows(IllegalArgumentException::class.java) {
                PrivacyCaseClient(
                    TestEdges.builder(),
                    TestEdges.passThrough,
                    TestEdges.clock,
                    MockEnvironment().withProperty("console.evidence-svc-url", blank),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            PrivacyCaseClient(TestEdges.builder(), TestEdges.passThrough, TestEdges.clock, MockEnvironment())
        }
    }
}
