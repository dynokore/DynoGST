package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.privacy.CorrectedVersion
import com.dynokore.gst.consoleBff.application.privacy.CorrectionRequest
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Instant

/** INV-AC-27140: CounterpartyCorrectionClient over the masterdata edge and a stub masterdata-svc. */
class CounterpartyCorrectionClientTest {
    private val stub = HeaderStub()
    private val case = "22222222-2222-2222-2222-222222222222"
    private val key = "33333333-3333-3333-3333-333333333333"
    private val version =
        """{"version":2,"recordedAt":"2026-11-14T10:00:00Z","current":true,"counterparty":{}}"""

    @AfterEach
    fun stop() = stub.stop()

    private fun client(registry: ResilienceRegistry = TestEdges.passThrough) =
        CounterpartyCorrectionClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.MASTERDATA.urlProperty, stub.url),
        )

    private fun request(
        caseId: String = case,
        field: String = "legalName",
    ) = CorrectionRequest("entity-1", caseId, field, "New Name", "typo")

    private fun correct(
        registry: ResilienceRegistry = TestEdges.passThrough,
        ref: String = "cp-1",
        idempotencyKey: String = key,
        request: CorrectionRequest = request(),
    ) = CurrentEndUserToken.callWith("Bearer u-1") { client(registry).correct(ref, idempotencyKey, request) }

    @Test
    @DisplayName("INV-AC-27140 correction 200 gives Ok with recordedAt")
    fun ok() {
        stub.answer(200, version)
        assertEquals(OwnerReply.Ok(CorrectedVersion(Instant.parse("2026-11-14T10:00:00Z"))), correct())
    }

    @Test
    @DisplayName(
        "INV-AC-27140 correction request carries Idempotency-Key and entityRef, caseId, field, correctedValue, reason",
    )
    fun requestShape() {
        stub.answer(200, version)
        correct()
        val seen = stub.seen.single()
        assertEquals("/internal/v1/masterdata/counterparties/cp-1/corrections", seen.path)
        assertEquals(key, seen.headers["idempotency-key"])
        val body = OwnerEnvelope.tree(seen.body)!!
        assertEquals(setOf("entityRef", "caseId", "field", "correctedValue", "reason"), body.propertyNames().toSet())
        assertEquals("entity-1", OwnerEnvelope.text(body, "entityRef"))
        assertEquals(case, OwnerEnvelope.text(body, "caseId"))
        assertEquals("legalName", OwnerEnvelope.text(body, "field"))
        assertEquals("New Name", OwnerEnvelope.text(body, "correctedValue"))
        assertEquals("typo", OwnerEnvelope.text(body, "reason"))
    }

    @Test
    @DisplayName("INV-AC-27140 correction 404 and the owner's problem body are relayed unchanged")
    fun refusalsRelayed() {
        for ((status, code) in REFUSALS) {
            stub.answer(status, envelope(status, code))
            assertEquals(
                OwnerReply.Refused(OwnerRefusal(status, code, "T", "BLOCK", "MANUAL", "R", "D")),
                correct(),
                "status $status",
            )
        }
    }

    @Test
    @DisplayName("INV-AC-27140 correction 5xx is Unavailable, never Refused")
    fun serverError() {
        for (status in listOf(500, 502, 503)) {
            stub.answer(status, envelope(status, "SRT-500"))
            assertTrue(correct() is OwnerReply.Unavailable, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27140 correction refused connection, open breaker and timeout are Unavailable")
    fun connectionFailure() {
        stub.answer(200, version)
        assertTrue(correct(TestEdges.connectRefused) is OwnerReply.Unavailable)
        assertTrue(correct(TestEdges.breakerOpen) is OwnerReply.Unavailable)
        assertTrue(correct(TestEdges.timedOut) is OwnerReply.Unavailable)
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27140 correction unreadable success and problem bodies are Unavailable")
    fun unreadable() {
        for (body in UNREADABLE_BODIES + """{"version":2}""" + """{"recordedAt":"yesterday"}""") {
            stub.answer(200, body)
            assertTrue(correct() is OwnerReply.Unavailable, "200 $body")
            stub.answer(422, body)
            assertTrue(correct() is OwnerReply.Unavailable, "422 $body")
        }
        stub.answer(201, version)
        assertTrue(correct() is OwnerReply.Unavailable)
    }

    @Test
    @DisplayName(
        "INV-AC-27140 correction with an invalid case id, key, field or reference is Unavailable without a call",
    )
    fun invalidInputs() {
        stub.answer(200, version)
        assertTrue(correct(request = request(caseId = "not-a-uuid")) is OwnerReply.Unavailable)
        assertTrue(correct(idempotencyKey = "1-1-1-1-1") is OwnerReply.Unavailable)
        assertTrue(correct(request = request(field = "taxId")) is OwnerReply.Unavailable)
        assertTrue(correct(request = request(field = "LEGAL_NAME")) is OwnerReply.Unavailable)
        assertTrue(correct(ref = " ") is OwnerReply.Unavailable)
        assertEquals(0, stub.seen.size)
    }
}
