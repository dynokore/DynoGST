package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.retention.RetentionService
import com.dynokore.gst.evidence.retention.TestIdentity
import com.dynokore.gst.evidence.retention.WorkloadTokensOnly
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-1812 end to end (INV-REQ-645): the document is sealed through the consumer handlers of the service as TPL-18
 * composes it (PayloadReady, SubmissionDespatched, SubmissionAcknowledged over PostgreSQL), then read over HTTP by
 * `getTransmittedVersion`. The permission `doc.view.content` is held by the replica grant of [READER] only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransmittedVersionWiringTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var service: RetentionService

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("tvw")
        service = RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java))
    }

    @AfterAll
    fun stop() {
        service.close()
        dir.toFile().deleteRecursively()
    }

    private val v1 = "<Invoice>one</Invoice>".toByteArray()

    // not valid UTF-8: a re-encoding of the bytes as text would change them
    private val v2 = byteArrayOf(0x3C, 0x49, 0x3E, 0xC3.toByte(), 0x28, 0xFF.toByte(), 0x3C, 0x2F, 0x49, 0x3E)
    private val sent = Instant.parse("2026-10-20T10:00:00Z")
    private val ack1 = Instant.parse("2026-10-20T10:05:00Z")
    private val ack2 = Instant.parse("2026-10-27T11:06:00Z")

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun envelope(
        tenant: TenantId,
        doc: UUID,
        type: String,
        time: Instant,
        data: String,
    ) = Envelope(
        id = EventId("E-${UUID.randomUUID()}"),
        source = "/gst/test",
        type = type,
        subject = "s",
        time = time,
        tenant = tenant,
        entity = EventEntityRef("ENT-1"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 1, 15),
        docSequence = 1,
        instanceId = "i-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun <T> inTenant(
        tenant: TenantId,
        body: () -> T,
    ): T =
        service.bean(TenantScope::class.java).bind(tenant, "svc:evidence-svc") {
            service.bean(TransactionRunner::class.java).inTransaction { body() }
        }

    /** The handler bean the consumer of its topic is built with (a qualifier by bean name). */
    private fun handler(name: String): EventHandler {
        val handlers = service.beansOf(EventHandler::class.java)
        return checkNotNull(handlers[name]) { "no handler bean $name among ${handlers.keys}" }
    }

    private fun deliver(
        tenant: TenantId,
        handler: EventHandler,
        event: Envelope,
    ) = inTenant(tenant) { handler.handle(event) }

    /** The locator is the CanonicalVersionCreated consumer's row; it is inserted as its store does. */
    private fun locate(
        tenant: TenantId,
        doc: UUID,
        version: Int,
    ) {
        EvidenceDb.superuser().use {
            RetentionPartitionFixture.ensureRetentionPartitions(it, tenant.value, "ENT-1", "2026-Q1")
        }
        inTenant(tenant) {
            val locator =
                DocumentLocator(doc, version, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace/$version", "INV-1")
            JooqLocatorStore(service.bean(DSLContext::class.java)).insert(tenant, "L-$doc-$version", locator, sent)
        }
    }

    private fun transmit(
        tenant: TenantId,
        doc: UUID,
        version: Int,
        bytes: ByteArray,
        at: Instant,
    ) {
        locate(tenant, doc, version)
        val digest = sha(bytes)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        deliver(
            tenant,
            handler("payloadReadyHandler"),
            envelope(
                tenant,
                doc,
                "com.dynokore.gst.payload.PayloadReady.v1",
                at,
                """{"docUuid":"$doc","version":$version,"payloadBytes":"$encoded","payloadSha256":"$digest"}""",
            ),
        )
        deliver(
            tenant,
            handler("submissionDespatchedHandler"),
            envelope(
                tenant,
                doc,
                "com.dynokore.gst.submission.SubmissionDespatched.v1",
                at,
                """{"docUuid":"$doc","version":$version,"attemptNumber":1,"payloadSha256":"$digest"}""",
            ),
        )
    }

    private fun acknowledge(
        tenant: TenantId,
        doc: UUID,
        version: Int,
        ackId: String,
        at: Instant,
    ) = deliver(
        tenant,
        handler("submissionAcknowledgedHandler"),
        envelope(
            tenant,
            doc,
            "com.dynokore.gst.submission.SubmissionAcknowledged.v1",
            at,
            """{"docUuid":"$doc","version":$version,"acknowledgementId":"$ackId","timestamp":"$at"}""",
        ),
    )

    private fun grant(
        tenant: TenantId,
        user: String,
    ) = service.grants.grant(tenant, user, service.grants.role(ROLE, GrantScope.TENANT, tenant.value.toString()))

    private class Reply(
        val status: Int,
        val body: JsonNode,
    )

    private fun get(
        doc: UUID,
        version: Int,
        user: String?,
        tenant: TenantId,
    ): Reply {
        val port = service.bean(org.springframework.core.env.Environment::class.java).getProperty("local.server.port")
        val uri = "http://localhost:$port/internal/v1/evidence/documents/$doc/versions/$version/transmitted-xml"
        val request =
            HttpRequest
                .newBuilder(URI.create(uri))
                .apply { user?.let { header("Authorization", "Bearer ${TestIdentity.token(it, tenant.value)}") } }
                .header("X-Workload-Token", "token-console-bff")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), mapper.readTree(response.body()))
    }

    private class Sealed(
        val tenant: TenantId,
        val doc: UUID,
    )

    /** Version 1 and its correction version 2, each transmitted and acknowledged with its own identifier. */
    private fun corrected(): Sealed {
        val tenant = TenantId(UUID.randomUUID())
        val doc = UUID.randomUUID()
        transmit(tenant, doc, 1, v1, sent)
        acknowledge(tenant, doc, 1, "ACK-ONE", ack1)
        transmit(tenant, doc, 2, v2, sent.plusSeconds(DAY))
        acknowledge(tenant, doc, 2, "ACK-TWO", ack2)
        grant(tenant, READER)
        return Sealed(tenant, doc)
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the case name is fixed by the task
    fun `INV-AC-1812 both transmitted versions of a corrected document are returned byte-identical with their identifiers`() {
        val s = corrected()
        val one = get(s.doc, 1, READER, s.tenant)
        val two = get(s.doc, 2, READER, s.tenant)
        one.status shouldBe 200
        two.status shouldBe 200
        Base64.getDecoder().decode(one.body.text("xmlBase64")).contentEquals(v1) shouldBe true
        Base64.getDecoder().decode(two.body.text("xmlBase64")).contentEquals(v2) shouldBe true
        one.body.text("xmlSha256") shouldBe sha(v1)
        two.body.text("xmlSha256") shouldBe sha(v2)
        one.body.text("docUuid") shouldBe s.doc.toString()
        two.body.text("docUuid") shouldBe s.doc.toString()
        one.body.get("version").asInt() shouldBe 1
        two.body.get("version").asInt() shouldBe 2
        one.body.text("acknowledgementId") shouldBe "ACK-ONE"
        two.body.text("acknowledgementId") shouldBe "ACK-TWO"
        Instant.parse(one.body.text("acknowledgedAt")) shouldBe ack1
        Instant.parse(two.body.text("acknowledgedAt")) shouldBe ack2
        Instant.parse(one.body.text("submittedAt")) shouldBe sent
        Instant.parse(two.body.text("submittedAt")) shouldBe sent.plusSeconds(DAY)
    }

    @Test
    fun `INV-AC-1812 a version despatched but not acknowledged carries no acknowledgement identifier or time`() {
        val tenant = TenantId(UUID.randomUUID())
        val doc = UUID.randomUUID()
        transmit(tenant, doc, 1, v1, sent)
        grant(tenant, READER)
        val answer = get(doc, 1, READER, tenant)
        answer.status shouldBe 200
        Base64.getDecoder().decode(answer.body.text("xmlBase64")).contentEquals(v1) shouldBe true
        answer.body.has("acknowledgementId") shouldBe false
        answer.body.has("acknowledgedAt") shouldBe false
        Instant.parse(answer.body.text("submittedAt")) shouldBe sent
    }

    @Test
    fun `INV-AC-1812 a denied caller, no caller, an unknown version and another tenant are refused without bytes`() {
        val s = corrected()
        val denied = get(s.doc, 1, DENIED, s.tenant)
        denied.status shouldBe 403
        denied.body.has("xmlBase64") shouldBe false
        get(s.doc, 1, null, s.tenant).status shouldBe 401
        val unknown = get(s.doc, 3, READER, s.tenant)
        unknown.status shouldBe 404
        unknown.body.text("code") shouldBe "SRT-005"
        val other = TenantId(UUID.randomUUID())
        grant(other, READER)
        val foreign = get(s.doc, 1, READER, other)
        foreign.status shouldBe 404
        foreign.body.has("xmlBase64") shouldBe false
    }

    private companion object {
        const val READER = "u-reader"
        const val DENIED = "u-denied"
        const val ROLE = "auditor"
        const val DAY = 86_400L
    }
}
