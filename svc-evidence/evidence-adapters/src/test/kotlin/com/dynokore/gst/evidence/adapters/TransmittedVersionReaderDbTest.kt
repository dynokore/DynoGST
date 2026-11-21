package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.application.lookup.StoredTransmittedVersion
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** INV-AC-26262 (database half): one transmitted version is read from the sealed record, byte for byte. */
class TransmittedVersionReaderDbTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-0000000000b2")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
    private val v1 = "<Invoice>one</Invoice>".toByteArray()
    private val v2 = byteArrayOf(0x3C, 0x49, 0x3E, 0xC3.toByte(), 0x28, 0x3C, 0x2F, 0x49, 0x3E)

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext, TenantId) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES), TenantId(tenant)).also { c.commit() }
        }

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun content(
        version: Int,
        attempt: Int,
        bytes: ByteArray,
        digest: String = sha(bytes),
        despatched: Instant = at,
    ) = (
        "{\"docUuid\":\"$doc\",\"version\":$version,\"attemptNumber\":$attempt,\"payloadSha256\":\"$digest\"," +
            "\"despatchedAt\":\"$despatched\",\"payloadBase64\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"
    ).toByteArray()

    private fun locate(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t ->
        JooqLocatorStore(dsl).insert(
            t,
            "L$version",
            DocumentLocator(doc, version, "ENT-1", "2026-Q1", LocalDate.of(2026, 1, 15), "trace/$version", "INV-1"),
            at,
        )
    }

    private fun seal(
        tenant: UUID,
        version: Int,
        eventId: String,
        content: ByteArray,
    ) = tx(tenant) { dsl, t ->
        RetentionPartitionFixture.ensureRetentionPartition(dsl, "evidence_payload", tenant, "ENT-1", "2026-Q1")
        JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl, Clock.fixed(at, ZoneOffset.UTC)))
            .append(t, doc, version, eventId, content, at)
    }

    private fun index(
        tenant: UUID,
        version: Int,
        ackId: String?,
        ackAt: Instant?,
    ) = tx(tenant) { dsl, t ->
        dsl.execute(
            "insert into evidence.submission_index " +
                "(tenant_id, doc_uuid, version, ack_id, acknowledged_at, recorded_at) " +
                "values (?, ?, ?, ?, ?::timestamptz, now())",
            t.value,
            doc,
            version,
            ackId,
            ackAt?.toString(),
        )
    }

    private fun read(
        tenant: UUID,
        version: Int,
    ) = tx(tenant) { dsl, t -> ChainWiring.transmittedVersionReader(dsl).read(t, doc, version) }

    private val ackAt = Instant.parse("2026-10-21T09:30:00Z")

    private fun fixture(): UUID {
        val t = UUID.randomUUID()
        locate(t, 1)
        locate(t, 2)
        seal(t, 1, "E1-${UUID.randomUUID()}", content(1, 1, v1))
        seal(t, 2, "E2-${UUID.randomUUID()}", content(2, 1, v2))
        index(t, 1, null, null)
        index(t, 2, "ACK-2", ackAt)
        return t
    }

    @Test
    fun `INV-AC-26262 each version returns its own sealed bytes and its acknowledgement`() {
        val t = fixture()
        val one = read(t, 1).shouldBeInstanceOf<StoredTransmittedVersion.Valid>()
        one.xml().contentEquals(v1) shouldBe true
        one.xmlSha256 shouldBe sha(v1)
        one.acknowledgementId shouldBe null
        one.acknowledgedAt shouldBe null
        one.submittedAt shouldBe at
        val two = read(t, 2).shouldBeInstanceOf<StoredTransmittedVersion.Valid>()
        two.xml().contentEquals(v2) shouldBe true
        two.xmlSha256 shouldBe sha(v2)
        two.acknowledgementId shouldBe "ACK-2"
        two.acknowledgedAt shouldBe ackAt
    }

    @Test
    fun `INV-AC-26262 another tenant's version 1 and an unknown version 3 are NotFound`() {
        val t = fixture()
        val other = UUID.randomUUID()
        locate(other, 1)
        seal(other, 1, "O1-${UUID.randomUUID()}", content(1, 1, "other".toByteArray()))
        read(t, 3) shouldBe null
        read(UUID.randomUUID(), 1) shouldBe null
        val theirs = read(other, 1).shouldBeInstanceOf<StoredTransmittedVersion.Valid>()
        theirs.xml().contentEquals("other".toByteArray()) shouldBe true
        read(t, 1).shouldBeInstanceOf<StoredTransmittedVersion.Valid>().xml().contentEquals(v1) shouldBe true
    }

    @Test
    fun `INV-AC-26262 two attempts of one version return the record of the highest attempt`() {
        val t = UUID.randomUUID()
        locate(t, 1)
        val later = Instant.parse("2026-10-20T12:00:00Z")
        seal(t, 1, "A2-${UUID.randomUUID()}", content(1, 2, v1, despatched = later))
        seal(t, 1, "A1-${UUID.randomUUID()}", content(1, 1, v1))
        read(t, 1).shouldBeInstanceOf<StoredTransmittedVersion.Valid>().submittedAt shouldBe later
    }

    @Test
    fun `INV-AC-26262 a stored record whose bytes do not hash to its digest is Unreadable`() {
        val t = UUID.randomUUID()
        locate(t, 1)
        seal(t, 1, "X-${UUID.randomUUID()}", content(1, 1, "tampered".toByteArray(), digest = sha(v1)))
        read(t, 1) shouldBe StoredTransmittedVersion.Unreadable
    }

    @Test
    fun `INV-AC-26262 a sealed_payload row without its applied_record row is Unreadable`() {
        val t = UUID.randomUUID()
        locate(t, 1)
        seal(t, 1, "OK-${UUID.randomUUID()}", content(1, 1, v1))
        tx(t) { dsl, tenant ->
            dsl.execute(
                "insert into evidence.sealed_payload (tenant_id, doc_uuid, version, source_event_id, recorded_at) " +
                    "values (?, ?, 1, 'GHOST', now())",
                tenant.value,
                doc,
            )
        }
        read(t, 1) shouldBe StoredTransmittedVersion.Unreadable
    }
}
