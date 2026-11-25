package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.renditionevidence.EvidencePage
import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckArtefactRecord
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Instant

/** INV-AC-27606: SampleCheckReadClient over the evidence edge and a stub evidence-svc. */
class SampleCheckReadClientTest {
    private val stub = PageStub()
    private val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val record =
        """{"artefactType":"SAMPLE_CHECK","reference":"SC-2026-0001","hash":"$hash",""" +
            """"recordedAt":"2026-04-01T01:00:00Z"}"""
    private val expected =
        SampleCheckArtefactRecord("SAMPLE_CHECK", "SC-2026-0001", hash, Instant.parse("2026-04-01T01:00:00Z"))

    @AfterEach
    fun stop() = stub.stop()

    private fun page(
        registry: ResilienceRegistry = TestEdges.passThrough,
        entityRef: String = "E1",
        cursor: String? = null,
    ) = CurrentEndUserToken.callWith("Bearer u-1") {
        SampleCheckReadClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.EVIDENCE.urlProperty, stub.url),
        ).page(entityRef, 25, cursor)
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks a full page is read with its nextCursor, a last page without one")
    fun readsPages() {
        stub.answer(200, """{"items":[$record],"nextCursor":"n-2"}""")
        assertEquals(PageReply.Ok(EvidencePage(listOf(expected), "n-2")), page())
        stub.answer(200, """{"items":[$record]}""")
        assertEquals(PageReply.Ok(EvidencePage(listOf(expected), null)), page())
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks the request is the contract GET with the limit, the cursor and the token")
    fun requestShape() {
        stub.answer(200, """{"items":[]}""")
        page(cursor = "c-9")
        val seen = stub.seen.single()
        assertEquals("GET", seen.method)
        assertEquals("/internal/v1/evidence/entities/E1/rendition-sample-checks", seen.rawPath)
        assertEquals(listOf("limit" to "25", "cursor" to "c-9"), seen.query)
        assertEquals("Bearer u-1", seen.authorization)
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks an opaque cursor reaches the owner unchanged")
    fun cursorUnchanged() {
        for (cursor in HOSTILE_CURSORS) {
            stub.answer(200, """{"items":[]}""")
            val reply = page(cursor = cursor)
            val seen = stub.seen.single()
            assertEquals(listOf("limit" to "25", "cursor" to cursor), seen.query, "$cursor $reply")
            // RFC 3986: a '+' is never left literal or used for a space
            assertTrue('+' !in seen.rawQuery, seen.rawQuery)
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks a blank or dot-segment entityRef is never sent")
    fun neverSent() {
        for (bad in listOf("", " ", ".", "..")) {
            stub.answer(200, """{"items":[]}""")
            assertTrue(page(entityRef = bad) is PageReply.Unavailable, "'$bad'")
            assertEquals(0, stub.seen.size, "'$bad'")
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks a response lacking hash or any other member is unreadable")
    fun missingMember() {
        for (member in listOf("artefactType", "reference", "hash", "recordedAt")) {
            val without = without(record, member)
            stub.answer(200, """{"items":[$record,$without]}""")
            assertTrue(page() is PageReply.Unavailable, "lacking $member")
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks an ill typed member or hash is unreadable")
    fun illTyped() {
        val items =
            listOf(
                record.replace(hash, hash.uppercase()),
                record.replace(hash, hash.dropLast(1)),
                record.replace(hash, "${hash}0"),
                record.replace(hash, "sha256:$hash"),
                record.replace("\"$hash\"", "1"),
                record.replace("2026-04-01T01:00:00Z", "2026-04-01"),
                record.replace("\"SC-2026-0001\"", "null"),
            )
        for (item in items) {
            stub.answer(200, """{"items":[$item]}""")
            assertTrue(page() is PageReply.Unavailable, item)
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks the owner's 400, 403 and 404 are relayed unchanged")
    fun refusalsRelayed() {
        for (status in listOf(400, 403, 404)) {
            stub.answer(status, envelope(status, "SRT-$status"))
            val refusal = OwnerRefusal(status, "SRT-$status", "T", "BLOCK", "MANUAL", "R", "D")
            val expected =
                when (status) {
                    400 -> PageReply.Invalid(refusal)
                    403 -> PageReply.Forbidden(refusal)
                    else -> PageReply.NotFound(refusal)
                }
            assertEquals(expected, page(), "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks an answer with a malformed Content-Type is Unavailable")
    fun malformedContentType() {
        for (contentType in MALFORMED_CONTENT_TYPES) {
            for (status in listOf(200, 403, 500)) {
                val body = if (status == 200) """{"items":[]}""" else envelope(status, "SRT-$status")
                stub.answer(status, body, contentType)
                assertTrue(page() is PageReply.Unavailable, "$status $contentType")
                assertEquals(1, stub.seen.size, "$status $contentType")
            }
        }
    }

    @Test
    @DisplayName("INV-AC-27606 sample checks 5xx, a refused connection, an open breaker and a timeout are Unavailable")
    fun unavailable() {
        for (status in listOf(500, 503)) {
            stub.answer(status, envelope(status, "SRT-$status"))
            assertTrue(page() is PageReply.Unavailable, "status $status")
        }
        assertTrue(page(TestEdges.connectRefused) is PageReply.Unavailable)
        assertTrue(page(TestEdges.breakerOpen) is PageReply.Unavailable)
        assertTrue(page(TestEdges.timedOut) is PageReply.Unavailable)
    }

    @Test
    @DisplayName("INV-AC-27606 a missing, blank or unresolved console.evidence-svc-url stops the start")
    fun baseUrlRequired() {
        val property = OwningService.EVIDENCE.urlProperty
        val client = SampleCheckReadClient::class.java
        assertNull(startFailure(client, property to stub.url))
        assertTrue(namesProperty(startFailure(client, "console.tenancy-svc-url" to stub.url), property), "absent")
        for (bad in BAD_BASE_URLS) {
            val failure = startFailure(client, property to bad)
            // an unresolved placeholder fails in the environment, which names the placeholder
            val named = namesProperty(failure, property) || namesProperty(failure, "CONSOLE_UNSET_IN_TEST")
            assertTrue(named, "'$bad' $failure")
        }
    }
}
