package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.application.renditionevidence.SampleMeta
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleMetaReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Clock

private const val METADATA_PATH = "/internal/v1/payload/entities/ENT-0001/rendition-sample-checks/chk-0001/metadata"

/** INV-AC-27783: PayloadSampleMetaClient over the PAYLOAD edge and a stub payload-svc. */
class PayloadSampleMetaClientTest {
    private val stub = OwnerStub()
    private val check = SampleVector.META_CHECK
    private val vectorMeta =
        SampleMeta(
            checkId = SampleVector.CHECK,
            entityRef = SampleVector.ENTITY,
            scenario = "SELF_BILLING",
            submissionType = "TYPE_2",
            sampleSha256 = SampleVector.SHA256,
            result = "FAILED",
            textFound = true,
            missingWording = SampleVector.MISSING_WORDING,
            checkedAt = SampleVector.CHECKED_AT,
            checkedBy = SampleVector.CHECKED_BY,
            findingCode = "PAY-014",
        )
    private val expected = SampleReply.Ok(SampleMetaReply(vectorMeta, standingOpen = true))

    @AfterEach
    fun stop() = stub.stop()

    private fun read(
        registry: ResilienceRegistry = TestEdges.passThrough,
        url: String = stub.url,
        clock: Clock = TestEdges.clock,
        entityRef: String = SampleVector.ENTITY,
        checkId: String = SampleVector.CHECK,
    ): SampleReply<SampleMetaReply> =
        CurrentEndUserToken.callWith("Bearer u-1") {
            PayloadSampleMetaClient(
                TestEdges.builder(),
                registry,
                clock,
                MockEnvironment().withProperty(OwningService.PAYLOAD.urlProperty, url),
            ).read(entityRef, checkId)
        }

    private fun answered(body: String): SampleReply<SampleMetaReply> {
        stub.answer(200, body)
        return read()
    }

    private fun assertUnavailable(
        reply: SampleReply<SampleMetaReply>,
        case: String,
    ) = assertTrue(reply is SampleReply.Unavailable, "$case: $reply")

    @Test
    @DisplayName("INV-AC-27783 metadata the vector without bytes gives the SampleMetaReply with standingOpen true")
    fun vector() {
        assertEquals(expected, answered(SampleVector.body(check)))
        assertEquals(
            SampleReply.Ok(SampleMetaReply(vectorMeta, standingOpen = false)),
            answered(SampleVector.body(check, """{"open":false}""")),
        )
        assertEquals(
            SampleReply.Ok(SampleMetaReply(vectorMeta.copy(findingCode = null), standingOpen = true)),
            answered(SampleVector.body(without(check, "findingCode"))),
        )
    }

    @Test
    @DisplayName("INV-AC-27783 metadata only paths ending in /metadata are requested, once, with the user's token")
    fun onlyMetadataPaths() {
        val requested = mutableListOf<String>()
        // a success, refusals and failures: none of them makes the client turn to the byte-bearing read
        val answers =
            listOf(200 to SampleVector.body(check), 200 to "{}", 403 to envelope(403, "SRT-403")) +
                listOf(404 to envelope(404, "SRT-404"), 500 to envelope(500, "SRT-500"))
        for ((status, body) in answers) {
            stub.answer(status, body)
            read()
            val seen = stub.seen.single()
            assertEquals("GET", seen.method)
            assertEquals("Bearer u-1", seen.authorization)
            requested += seen.path
        }
        assertTrue(requested.all { it.endsWith("/metadata") }, "$requested")
        assertEquals(List(answers.size) { METADATA_PATH }, requested)
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a missing member or standing gives Unavailable and never a default")
    fun missingMember() {
        for (member in SampleVector.REQUIRED) {
            assertUnavailable(answered(SampleVector.body(without(check, member))), "lacking $member")
        }
        assertUnavailable(answered(SampleVector.body(check, standing = null)), "lacking standing")
        val standings = listOf("null", "{}", "true", "[]", """{"open":null}""", """{"open":"true"}""", """{"open":1}""")
        for (standing in standings) {
            assertUnavailable(answered(SampleVector.body(check, standing)), "standing $standing")
        }
        for (body in listOf("{}", """{"standing":{"open":true}}""", """{"check":null,"standing":{"open":true}}""")) {
            assertUnavailable(answered(body), body)
        }
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a sampleSha256 that is not 64 lowercase hex characters gives Unavailable")
    fun sampleSha256Form() {
        val sha = SampleVector.SHA256
        val bad =
            listOf(sha.dropLast(1), sha + "0", sha.uppercase(), sha.replaceFirst('e', 'g'), " $sha", "$sha\\n", "")
        for (value in bad) {
            val answer = SampleVector.with(check, "sampleSha256", "\"$value\"")
            assertUnavailable(answered(SampleVector.body(answer)), "'$value'")
        }
        assertEquals(63, bad.first().length)
    }

    @Test
    @DisplayName("INV-AC-27783 metadata an ill typed member gives Unavailable")
    fun illTyped() {
        for ((member, value) in SampleVector.ILL_TYPED) {
            val answer = SampleVector.with(check, member, value)
            assertUnavailable(answered(SampleVector.body(answer)), "$member=$value")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 metadata an extra sampleContent or artefactBytes is accepted and not passed on")
    fun extraMembers() {
        val extras =
            listOf(
                """"sampleContent":"U0VDUkVU"""",
                """"artefactBytes":"U0VDUkVU"""",
                """"sampleContent":7,"artefactBytes":null""",
                """"surprise":{"a":1}""",
            )
        for (extra in extras) {
            val reply = answered(SampleVector.body(SampleVector.adding(check, extra)))
            assertEquals(expected, reply, extra)
            assertFalse("U0VDUkVU" in reply.toString(), reply.toString())
        }
        assertEquals(expected, answered(SampleVector.adding(SampleVector.body(check), """"sampleContent":"x"""")))
    }

    @Test
    @DisplayName("INV-AC-27783 metadata 400, 403 and 404 with a readable envelope are relayed unchanged")
    fun refusalsRelayed() {
        val relayed =
            mapOf(
                400 to SampleReply.Invalid(stubRefusal(400)),
                403 to SampleReply.Forbidden(stubRefusal(403)),
                404 to SampleReply.NotFound(stubRefusal(404)),
            )
        for ((status, reply) in relayed) {
            stub.answer(status, envelope(status, "SRT-$status"))
            assertEquals(reply, read(), "status $status")
            assertEquals(1, stub.seen.size, "status $status")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a 409, an unreadable 400, a 500 and every other failure give Unavailable")
    fun otherFailures() {
        for ((status, body) in notRelayed()) {
            stub.answer(status, body)
            assertUnavailable(read(), "$status $body")
            assertEquals(1, stub.seen.size, "no retry for $status")
        }
        for (status in listOf(201, 202, 204, 206)) {
            stub.answer(status, if (status == 204) null else SampleVector.body(check))
            assertUnavailable(read(), "status $status")
        }
        for (body in UNREADABLE_BODIES) {
            stub.answer(200, body)
            assertUnavailable(read(), "200 $body")
        }
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a delayed answer beyond the 2000 ms edge timeout gives Unavailable")
    fun delayed() {
        payloadEdgePolicy().use { registry ->
            DelayedStub(OwningService.PAYLOAD.timeout.toMillis() + 1500, SampleVector.body(check)).use { slow ->
                val reply = read(registry, slow.url, Clock.systemUTC())
                assertEquals(SampleReply.Unavailable("console-bff->payload-svc: timed out"), reply)
            }
            DelayedStub(0, SampleVector.body(check)).use { quick ->
                assertEquals(expected, read(registry, quick.url, Clock.systemUTC()))
            }
        }
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a blank or dot-segment entityRef or checkId is never sent")
    fun pathSegments() {
        stub.answer(200, SampleVector.body(check))
        for (bad in listOf("", " ", ".", "..")) {
            assertUnavailable(read(entityRef = bad), "entityRef '$bad'")
            assertUnavailable(read(checkId = bad), "checkId '$bad'")
        }
        assertEquals(0, stub.seen.size)
    }

    @Test
    @DisplayName("INV-AC-27783 metadata a missing, blank or unresolved console.payload-svc-url stops the start")
    fun baseUrlRequired() {
        val property = OwningService.PAYLOAD.urlProperty
        val client = PayloadSampleMetaClient::class.java
        assertNull(startFailure(client, property to stub.url))
        for (bad in BAD_BASE_URLS) {
            val failure = startFailure(client, property to bad)
            val named = namesProperty(failure, property) || namesProperty(failure, "CONSOLE_UNSET_IN_TEST")
            assertTrue(named, "'$bad' $failure")
        }
    }
}
