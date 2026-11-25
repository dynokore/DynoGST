package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.EvidencePage
import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/** One request a [PageStub] received: the raw path and the decoded query parameters. */
internal data class PageRequest(
    val method: String,
    val rawPath: String,
    val rawQuery: String,
    val query: List<Pair<String, String>>,
    val authorization: String?,
)

/** A stub owning service for a cursor list: it records the query too. */
internal class PageStub {
    @Volatile private var status = 200

    @Volatile private var body: String? = null

    @Volatile private var contentType = JSON
    val seen = CopyOnWriteArrayList<PageRequest>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { x ->
                val query =
                    x.requestURI.rawQuery
                        .orEmpty()
                        .split('&')
                        .filter { it.isNotEmpty() }
                        .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
                seen +=
                    PageRequest(
                        x.requestMethod,
                        x.requestURI.rawPath,
                        x.requestURI.rawQuery.orEmpty(),
                        query,
                        x.requestHeaders.getFirst("Authorization"),
                    )
                val bytes = body?.toByteArray()
                if (bytes != null) x.responseHeaders.add("Content-Type", contentType)
                x.sendResponseHeaders(status, bytes?.size?.toLong() ?: -1)
                bytes?.let { x.responseBody.write(it) }
                x.close()
            }
            start()
        }
    val url = "http://127.0.0.1:${server.address.port}"

    // a server-side form decoding: a '+' that was not encoded arrives as a space, as in the owners' servlet stack
    private fun decode(text: String) = URLDecoder.decode(text, Charsets.UTF_8)

    fun answer(
        status: Int,
        body: String?,
        contentType: String = JSON,
    ) {
        this.status = status
        this.body = body
        this.contentType = contentType
        seen.clear()
    }

    fun stop() = server.stop(0)

    private companion object {
        const val JSON = "application/json"
    }
}

/** Starts a context that holds only the client and what it injects; the failure of the start, or null. */
internal fun startFailure(
    client: Class<*>,
    vararg properties: Pair<String, String>,
): Throwable? =
    runCatching {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
            context.registerBean(
                "internalRestClientBuilder",
                RestClient.Builder::class.java,
                Supplier { TestEdges.builder() },
            )
            context.registerBean(ResilienceRegistry::class.java, Supplier { TestEdges.passThrough })
            context.registerBean(Clock::class.java, Supplier { TestEdges.clock })
            context.register(client)
            context.refresh()
            context.getBean(client)
        }
    }.exceptionOrNull()

/** A start failure caused by the base URL of [property], not by anything else of the context. */
internal fun namesProperty(
    failure: Throwable?,
    property: String,
): Boolean = generateSequence(failure) { it.cause }.any { property in it.message.orEmpty() }

/** The base URL values that must stop the start: blank, and an unresolved placeholder. */
internal val BAD_BASE_URLS = listOf("", "   ", "\${CONSOLE_UNSET_IN_TEST}", "\${CONSOLE_UNSET_IN_TEST}/x")

/** `Content-Type` values that do not parse. */
internal val MALFORMED_CONTENT_TYPES = listOf("garbage", "a/b;=", "*/json", "application/json;charset=bogus")

/** Cursors that a wrong query encoding would change or expand. */
internal val HOSTILE_CURSORS = listOf("ra1.a+b/c=.d", "x&limit=500", "{entityRef}", "{unknown}", "%41 #?")

/** INV-AC-27606: AttestationReadClient over the tenancy edge and a stub tenancy-svc. */
class AttestationReadClientTest {
    private val stub = PageStub()
    private val record =
        """{"documentClass":"TAX_INVOICE","renderer":"AGENT","attestedBy":"identity:USR-009",""" +
            """"attestedAt":"2026-10-18T09:30:00Z","sequence":7}"""
    private val expected =
        AttestationRecord("TAX_INVOICE", "AGENT", "identity:USR-009", Instant.parse("2026-10-18T09:30:00Z"), 7)

    @AfterEach
    fun stop() = stub.stop()

    private fun page(
        registry: ResilienceRegistry = TestEdges.passThrough,
        entityRef: String = "E1",
        cursor: String? = null,
        limit: Int = 25,
    ) = CurrentEndUserToken.callWith("Bearer u-1") {
        AttestationReadClient(
            TestEdges.builder(),
            registry,
            TestEdges.clock,
            MockEnvironment().withProperty(OwningService.TENANCY.urlProperty, stub.url),
        ).page(entityRef, limit, cursor)
    }

    @Test
    @DisplayName("INV-AC-27606 the tenancy and evidence edges have their names, properties and 2000 ms")
    fun edges() {
        assertEquals("console-bff->tenancy-svc", OwningService.TENANCY.edge)
        assertEquals("console.tenancy-svc-url", OwningService.TENANCY.urlProperty)
        assertEquals(Duration.ofMillis(2000), OwningService.TENANCY.timeout)
        assertEquals("console-bff->evidence-svc", OwningService.EVIDENCE.edge)
        assertEquals("console.evidence-svc-url", OwningService.EVIDENCE.urlProperty)
        assertEquals(Duration.ofMillis(2000), OwningService.EVIDENCE.timeout)
    }

    @Test
    @DisplayName("INV-AC-27606 attestations a full page is read with its nextCursor, a last page without one")
    fun readsPages() {
        stub.answer(200, """{"items":[$record],"nextCursor":"n-2"}""")
        assertEquals(PageReply.Ok(EvidencePage(listOf(expected), "n-2")), page())
        stub.answer(200, """{"items":[$record]}""")
        assertEquals(PageReply.Ok(EvidencePage(listOf(expected), null)), page())
        stub.answer(200, """{"items":[]}""")
        assertEquals(PageReply.Ok(EvidencePage(emptyList<AttestationRecord>(), null)), page())
    }

    @Test
    @DisplayName("INV-AC-27606 attestations the request is the contract GET with the limit, the cursor and the token")
    fun requestShape() {
        stub.answer(200, """{"items":[]}""")
        page(cursor = "c-1")
        val seen = stub.seen.single()
        assertEquals("GET", seen.method)
        assertEquals("/internal/v1/tenancy/entities/E1/rendition-attestations", seen.rawPath)
        assertEquals(listOf("limit" to "25", "cursor" to "c-1"), seen.query)
        assertEquals("Bearer u-1", seen.authorization)
        stub.answer(200, """{"items":[]}""")
        page()
        assertEquals(listOf("limit" to "25"), stub.seen.single().query)
    }

    @Test
    @DisplayName("INV-AC-27606 attestations an opaque cursor reaches the owner unchanged")
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
    @DisplayName("INV-AC-27606 attestations an entityRef cannot leave its path segment")
    fun entityRefStaysOneSegment() {
        for (bad in listOf("", " ", ".", "..")) {
            stub.answer(200, """{"items":[]}""")
            assertTrue(page(entityRef = bad) is PageReply.Unavailable, bad)
            assertEquals(0, stub.seen.size, bad)
        }
        stub.answer(200, """{"items":[]}""")
        page(entityRef = "E1/../E2?x=1")
        assertEquals(
            "/internal/v1/tenancy/entities/E1%2F..%2FE2%3Fx%3D1/rendition-attestations",
            stub.seen.single().rawPath,
        )
    }

    @Test
    @DisplayName("INV-AC-27606 attestations a response lacking sequence or any other member is unreadable")
    fun missingMember() {
        for (member in listOf("documentClass", "renderer", "attestedBy", "attestedAt", "sequence")) {
            val without = without(record, member)
            stub.answer(200, """{"items":[$record,$without]}""")
            assertTrue(page() is PageReply.Unavailable, "lacking $member")
        }
    }

    @Test
    @DisplayName("INV-AC-27606 attestations an ill typed member, page or cursor is unreadable")
    fun illTyped() {
        val items =
            listOf(
                record.replace("\"sequence\":7", "\"sequence\":\"7\""),
                record.replace("\"sequence\":7", "\"sequence\":7.5"),
                record.replace("\"sequence\":7", "\"sequence\":0"),
                record.replace("\"sequence\":7", "\"sequence\":null"),
                record.replace("\"sequence\":7", "\"sequence\":99999999999999999999"),
                record.replace("2026-10-18T09:30:00Z", "yesterday"),
                record.replace("\"AGENT\"", "3"),
                record.replace("\"sequence\"", "\"Sequence\""),
                "null",
                "3",
            ).map { """{"items":[$it]}""" }
        val pages =
            listOf(
                """{}""",
                """{"items":null}""",
                """{"items":{}}""",
                """{"Items":[]}""",
                """{"items":[],"nextCursor":null}""",
                """{"items":[],"nextCursor":""}""",
                """{"items":[],"nextCursor":4}""",
                """{"items":[]} trailing""",
            )
        for (body in items + pages + UNREADABLE_BODIES.filter { it == null || !it.startsWith("{") }) {
            stub.answer(200, body)
            assertTrue(page() is PageReply.Unavailable, "200 $body")
        }
        for (status in listOf(201, 202, 204, 206)) {
            stub.answer(status, if (status == 204) null else """{"items":[]}""")
            assertTrue(page() is PageReply.Unavailable, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27606 attestations the owner's 400, 403 and 404 are relayed unchanged")
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
    @DisplayName("INV-AC-27606 attestations other statuses and unreadable refusals are Unavailable")
    fun otherFailures() {
        for (status in listOf(401, 409, 422, 429, 500, 502, 503)) {
            stub.answer(status, envelope(status, "SRT-$status"))
            assertTrue(page() is PageReply.Unavailable, "status $status")
        }
        for (body in UNREADABLE_BODIES) {
            for (status in listOf(403, 404)) {
                stub.answer(status, body)
                assertTrue(page() is PageReply.Unavailable, "$status $body")
            }
        }
    }

    @Test
    @DisplayName("INV-AC-27606 attestations an answer with a malformed Content-Type is Unavailable")
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
    @DisplayName("INV-AC-27606 attestations refused connection, open breaker, timeout and a dead owner are Unavailable")
    fun noAnswer() {
        stub.answer(200, """{"items":[]}""")
        assertTrue(page(TestEdges.connectRefused) is PageReply.Unavailable)
        assertTrue(page(TestEdges.breakerOpen) is PageReply.Unavailable)
        assertTrue(page(TestEdges.timedOut) is PageReply.Unavailable)
        assertEquals(0, stub.seen.size)
        stub.stop()
        assertTrue(page() is PageReply.Unavailable)
    }

    @Test
    @DisplayName("INV-AC-27606 a missing, blank or unresolved console.tenancy-svc-url stops the start")
    fun baseUrlRequired() {
        val property = OwningService.TENANCY.urlProperty
        val client = AttestationReadClient::class.java
        assertNull(startFailure(client, property to stub.url))
        assertTrue(namesProperty(startFailure(client, "console.evidence-svc-url" to stub.url), property), "absent")
        for (bad in BAD_BASE_URLS) {
            val failure = startFailure(client, property to bad)
            // an unresolved placeholder fails in the environment, which names the placeholder
            val named = namesProperty(failure, property) || namesProperty(failure, "CONSOLE_UNSET_IN_TEST")
            assertTrue(named, "'$bad' $failure")
        }
    }
}

/** The JSON object text without one of its string or number members. */
internal fun without(
    json: String,
    member: String,
): String {
    val tree = checkNotNull(OwnerEnvelope.tree(json)) as tools.jackson.databind.node.ObjectNode
    checkNotNull(tree.remove(member)) { "$member is not a member" }
    return tree.toString()
}
