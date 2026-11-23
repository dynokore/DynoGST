package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-28217: an `EvidenceArtefactRecorded` row of a reconciliation work-paper carries `header.periodRef` of the
 * sealed object, over PostgreSQL with forced row-level security and a fake object store.
 */
class EvidenceArtefactPeriodDbTest {
    private val tenant = UUID.randomUUID()
    private val reference = "reconciliation/$tenant/R1/v1"

    private class Fake : ObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray {
            reads += key.value
            return objects[key.value] ?: error("no such object")
        }

        override fun bytesUnder(prefix: ObjectKeyPrefix): Long = 0
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).let { HexFormat.of().formatHex(it) }

    private fun paper(header: String) = """{"header":$header}""".toByteArray()

    private fun envelope(
        eventId: String,
        type: String,
        ref: String,
        hash: String,
    ) = Envelope(
        id = EventId(eventId),
        source = "/gst/recon-svc",
        type = "com.dynokore.gst.platform.EvidenceArtefactRecorded.v1",
        subject = "s",
        time = Instant.parse("2026-01-15T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EventEntityRef("ENT-3F2A9C"),
        docUuid = DocUuid(UUID.randomUUID()),
        transactionDate = LocalDate.of(2026, 1, 15),
        docSequence = 1,
        instanceId = "recon-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = """{"artefactType":"$type","reference":"$ref","hash":"$hash"}""",
    )

    private fun handle(
        store: Fake,
        vararg events: Envelope,
    ) = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
        c.autoCommit = false
        SignedBindings.bind(c, tenant)
        val handler = ChainWiring.evidenceArtefactHandler(DSL.using(c, SQLDialect.POSTGRES), store)
        try {
            events.forEach { handler.handle(it) }
            c.commit()
        } catch (e: ContractViolation) {
            c.rollback()
            throw e
        }
    }

    /** (artefact_type, period_ref) of every row of the tenant. */
    private fun rows(): List<Pair<String, String?>> =
        EvidenceDb.superuser().use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select artefact_type, period_ref from evidence.evidence_artefact " +
                            "where tenant_id = '$tenant' order by artefact_type, reference",
                    ).use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
                    }
            }
        }

    private fun refused(
        bytes: ByteArray,
        hash: String = sha(bytes),
    ) {
        val store = Fake()
        store.objects[reference] = bytes
        val event = envelope("e-bad", "RECONCILIATION_WORK_PAPER", reference, hash)
        shouldThrow<ContractViolation> { handle(store, event) }
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28217 a work-paper event stores one row whose period_ref equals the sealed header value`() {
        val bytes = paper("""{"periodRef":"2026-Q1"}""")
        val store = Fake().also { it.objects[reference] = bytes }
        handle(store, envelope("e1", "RECONCILIATION_WORK_PAPER", reference, sha(bytes)))
        rows() shouldBe listOf("RECONCILIATION_WORK_PAPER" to "2026-Q1")
    }

    @Test
    fun `INV-AC-28217 an object with no header periodRef raises ContractViolation and stores nothing`() {
        refused(paper("""{"entityRef":"E"}"""))
    }

    @Test
    fun `INV-AC-28217 a blank header periodRef raises ContractViolation and stores nothing`() {
        refused(paper("""{"periodRef":"  "}"""))
    }

    @Test
    fun `INV-AC-28217 bytes whose SHA-256 differs from the event hash raise ContractViolation and store nothing`() {
        refused(paper("""{"periodRef":"2026-Q1"}"""), hash = "a".repeat(HASH))
    }

    @Test
    fun `INV-AC-28217 a RENDITION event stores a row with a null period_ref and reads no object`() {
        val store = Fake()
        handle(store, envelope("e1", "RENDITION", "rendition/$tenant/x", "c".repeat(HASH)))
        rows() shouldBe listOf("RENDITION" to null)
        store.reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28217 a direct INSERT of a work-paper row with a null period_ref is refused by the check`() {
        val failure =
            EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, tenant)
                val e =
                    runCatching {
                        c.createStatement().use {
                            it.execute(
                                "insert into evidence.evidence_artefact (id, tenant_id, entity_ref, artefact_type, " +
                                    "reference, hash, event_id, recorded_at, period_ref) " +
                                    "values ('${UUID.randomUUID()}', '$tenant', 'E', 'RECONCILIATION_WORK_PAPER', " +
                                    "'r', '${"a".repeat(HASH)}', 'e', now(), null)",
                            )
                        }
                    }.exceptionOrNull()
                c.rollback()
                e as SQLException
            }
        failure.sqlState shouldBe "23514"
        failure.message!! shouldContain "evidence_artefact_work_paper_period"
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28217 the same event twice stores one row`() {
        val bytes = paper("""{"periodRef":"2026-Q1"}""")
        val store = Fake().also { it.objects[reference] = bytes }
        val event = envelope("e1", "RECONCILIATION_WORK_PAPER", reference, sha(bytes))
        handle(store, event, envelope("e2", "RECONCILIATION_WORK_PAPER", reference, sha(bytes)))
        rows() shouldBe listOf("RECONCILIATION_WORK_PAPER" to "2026-Q1")
    }

    private companion object {
        const val HASH = 64
    }
}
