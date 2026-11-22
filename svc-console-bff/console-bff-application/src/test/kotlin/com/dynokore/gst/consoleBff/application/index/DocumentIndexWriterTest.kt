package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldReader
import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedReader
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts
import com.dynokore.gst.consoleBff.domain.index.DocumentIndexRow
import com.dynokore.gst.consoleBff.domain.index.IndexState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class DocumentIndexWriterTest {
    private val tenantId = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val tenant = tenantId.value.toString()
    private val d1 = "00000000-0000-0000-0000-000000000001"
    private val d2 = "00000000-0000-0000-0000-000000000002"
    private val date = LocalDate.parse("2026-03-20")
    private val transactionDate = LocalDate.parse("2026-03-31")

    private class MemoryStore : DocumentIndexStore {
        val rows = linkedMapOf<Triple<String, String, Long>, DocumentIndexRow>()

        override fun upsert(row: DocumentIndexRow): Boolean {
            val key = Triple(row.tenantId, row.docUuid, row.version)
            if (key in rows) return false
            rows[key] = row
            return true
        }

        override fun update(
            tenantId: String,
            docUuid: String,
            version: Long,
            change: (DocumentIndexRow) -> DocumentIndexRow,
        ): Int {
            val key = Triple(tenantId, docUuid, version)
            val old = rows[key] ?: return 0
            val new = change(old)
            rows[key] = new
            return if (new != old) 1 else 0
        }

        override fun updateDocument(
            tenantId: String,
            docUuid: String,
            change: (DocumentIndexRow) -> DocumentIndexRow,
        ): Int =
            rows.keys
                .filter { it.first == tenantId && it.second == docUuid }
                .sumOf { update(it.first, it.second, it.third, change) }
    }

    private class Counter : IndexMetrics {
        var unscoped = 0

        override fun unscopedVersionEvent() {
            unscoped++
        }
    }

    private val store = MemoryStore()
    private val metrics = Counter()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenantId

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    // dataJson of the fake readers: "doc|version|entityRef-or-dash|verdict", "doc|version|ack-or-dash", "doc".
    private val versionReader =
        object : CanonicalVersionCreatedReader {
            override fun read(dataJson: String): CanonicalVersionFacts {
                val p = dataJson.split('|')
                return CanonicalVersionFacts(
                    docUuid = p[0],
                    version = p[1].toLong(),
                    entityRef = p[2].takeUnless { it == "-" },
                    documentNumber = "N1",
                    counterpartyName = "C1",
                    counterpartyUen = null,
                    amount = BigDecimal("100.00"),
                    documentDate = date,
                    categoryCodes = setOf("SR"),
                    scopeVerdict = p[3],
                    transactionDate = transactionDate,
                )
            }
        }
    private val heldReader =
        object : DocumentHeldReader {
            override fun read(
                dataJson: String,
                envelopeEntityRef: String?,
            ) = DocumentHeldFacts("E1", dataJson, 1, listOf("X"), "")
        }
    private val releasedReader =
        object : DocumentReleasedReader {
            override fun read(dataJson: String) = DocumentReleasedFacts(dataJson, 1, listOf("X"))
        }
    private val despatchedReader =
        object : SubmissionDespatchedReader {
            override fun read(dataJson: String) =
                dataJson.split('|').let { SubmissionVersionFacts(it[0], it[1].toLong()) }
        }
    private val acknowledgedReader =
        object : SubmissionAcknowledgedReader {
            override fun read(dataJson: String) =
                dataJson.split('|').let { SubmissionVersionFacts(it[0], it[1].toLong(), it[2]) }
        }

    // "doc|version|code-or-dash"; the event's docSequence (1 in every envelope) is deliberately not the version.
    private val failedReader =
        object : SubmissionFailedIndexReader {
            override fun read(dataJson: String) =
                dataJson.split('|').let {
                    SubmissionFailedIndexFacts(
                        it[0],
                        it[1].toLong(),
                        it[2].takeUnless { c ->
                            c ==
                                "-"
                        },
                    )
                }
        }
    private val resolvedReader =
        object : MasterDataResolvedUenReader {
            override fun read(dataJson: String) = ResolvedUen(dataJson.split(',').filter { it.isNotEmpty() }, "UEN-1")
        }

    private val version = CanonicalVersionCreatedHandler(tenants, versionReader, store, metrics)
    private val held = IndexDocumentHeldHandler(tenants, heldReader, store)
    private val released = IndexDocumentReleasedHandler(tenants, releasedReader, store)
    private val despatched = IndexSubmissionDespatchedHandler(tenants, despatchedReader, store)
    private val acknowledged = IndexSubmissionAcknowledgedHandler(tenants, acknowledgedReader, store)
    private val failed = IndexSubmissionFailedHandler(tenants, failedReader, store)
    private val resolved = IndexMasterDataResolvedHandler(tenants, resolvedReader, store)

    private fun envelope(data: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/test",
            type = "com.dynokore.gst.test.Event.v1",
            subject = "doc",
            time = Instant.parse("2026-11-12T09:00:00Z"),
            tenant = tenantId,
            entity = EntityRef("E1"),
            docUuid = DocUuid(UUID.fromString(d1)),
            transactionDate = null,
            docSequence = 1,
            instanceId = "test-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun run(
        handler: EventHandler,
        data: String,
    ) = handler.handle(envelope(data))

    private fun row(
        doc: String,
        v: Long,
    ) = store.rows[Triple(tenant, doc, v)]

    private fun playAll(): List<HandleOutcome> =
        listOf(
            run(version, "$d1|1|E1|SUBMITTABLE"),
            run(version, "$d1|2|E1|SUBMITTABLE"),
            run(version, "$d2|1|-|SUBMITTABLE"),
            run(held, d1),
            run(released, d1),
            run(despatched, "$d1|2"),
            run(acknowledged, "$d1|2|A1"),
            run(failed, "$d1|1|C-1"),
            run(resolved, d1),
        )

    @Test
    fun `INV-AC-26807 events build exactly the two rows with their columns`() {
        playAll()
        store.rows.keys.toList() shouldBe listOf(Triple(tenant, d1, 1L), Triple(tenant, d1, 2L))
        listOf(1L, 2L).forEach {
            val r = row(d1, it)!!
            r.tenantId shouldBe tenant
            r.entityRef shouldBe "E1"
            r.documentNumber shouldBe "N1"
            r.counterpartyName shouldBe "C1"
            r.amount shouldBe BigDecimal("100.00")
            r.documentDate shouldBe date
            r.transactionDate shouldBe transactionDate
            r.categoryCodes shouldBe setOf("SR")
        }
    }

    @Test
    fun `INV-AC-26807 version event without entityRef writes no row and counts once`() {
        run(version, "$d2|1|-|SUBMITTABLE") shouldBe HandleOutcome.IGNORED
        store.rows.isEmpty() shouldBe true
        metrics.unscoped shouldBe 1
        playAll()
        row(d2, 1) shouldBe null
        metrics.unscoped shouldBe 2
    }

    @Test
    fun `INV-AC-26807 states acknowledgement and error code after the sequence`() {
        playAll()
        row(d1, 2)!!.state shouldBe IndexState.ACKNOWLEDGED
        row(d1, 2)!!.acknowledgementId shouldBe "A1"
        row(d1, 1)!!.state shouldBe IndexState.READY
        row(d1, 1)!!.errorCode shouldBe "C-1"
        row(d1, 2)!!.errorCode shouldBe null
        row(d1, 1)!!.counterpartyUen shouldBe "UEN-1"
    }

    @Test
    fun `INV-AC-26807 state words of a version event and held and release`() {
        run(version, "$d1|1|E1|SUBMITTABLE") shouldBe HandleOutcome.APPLIED
        run(version, "$d1|2|E1|OUT_OF_SCOPE") shouldBe HandleOutcome.APPLIED
        row(d1, 2)!!.state shouldBe IndexState.NOT_SUBMITTABLE
        run(held, d1) shouldBe HandleOutcome.APPLIED
        row(d1, 1)!!.state shouldBe IndexState.HELD
        row(d1, 2)!!.state shouldBe IndexState.HELD
        run(released, d1) shouldBe HandleOutcome.APPLIED
        row(d1, 1)!!.state shouldBe IndexState.READY
    }

    @Test
    fun `INV-AC-26807 events for a version or document with no row create no row`() {
        run(held, d2) shouldBe HandleOutcome.IGNORED
        run(released, d2) shouldBe HandleOutcome.IGNORED
        run(despatched, "$d2|1") shouldBe HandleOutcome.IGNORED
        run(acknowledged, "$d2|1|A9") shouldBe HandleOutcome.IGNORED
        run(failed, "$d2|1|C-9") shouldBe HandleOutcome.IGNORED
        run(resolved, d2) shouldBe HandleOutcome.IGNORED
        store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-26807 failure error code goes to the payload version and a missing code changes nothing`() {
        run(version, "$d1|1|E1|SUBMITTABLE")
        run(version, "$d1|2|E1|SUBMITTABLE")
        run(failed, "$d1|2|C-2") shouldBe HandleOutcome.APPLIED
        row(d1, 2)!!.errorCode shouldBe "C-2"
        row(d1, 1)!!.errorCode shouldBe null
        run(failed, "$d1|1|-") shouldBe HandleOutcome.IGNORED
        row(d1, 1)!!.errorCode shouldBe null
        row(d1, 2)!!.state shouldBe IndexState.READY
    }

    @Test
    fun `INV-AC-26807 despatch never lowers ACKNOWLEDGED`() {
        run(version, "$d1|2|E1|SUBMITTABLE")
        run(acknowledged, "$d1|2|A1")
        run(despatched, "$d1|2") shouldBe HandleOutcome.IGNORED
        row(d1, 2)!!.state shouldBe IndexState.ACKNOWLEDGED
    }

    @Test
    fun `INV-AC-26807 replaying every event leaves the store unchanged`() {
        playAll()
        val before = store.rows.toMap()
        playAll()
        store.rows shouldBe before
    }
}
