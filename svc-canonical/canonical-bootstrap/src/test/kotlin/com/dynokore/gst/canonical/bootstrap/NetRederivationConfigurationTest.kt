package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqRecordedDocumentsByKey
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentRowWriter
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.NetDerivationCreditNoteFact
import com.dynokore.gst.canonical.application.inbound.InboundEventStore
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetDerivationRequestHandler
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.application.netting.NettingLinksDeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.RecordedDocumentsByKey
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** INV-AC-27994: the beans of the netting wiring of the record path in the full TPL-18 context. */
class NetRederivationConfigurationTest {
    private val actor = SignedBindings.ACTOR
    private val day: LocalDate = LocalDate.of(2026, 3, 20)

    @Test
    fun `INV-AC-27994 B1 exactly one bean of each netting type and the declared credit notes sit over the adapter`(
        @TempDir dir: Path,
    ) {
        CanonicalContext.online(dir).run { ctx ->
            ctx.getBeansOfType(NetRederivation::class.java).size shouldBe 1
            ctx.getBeansOfType(DeclaredCreditNotes::class.java).size shouldBe 1
            ctx.getBeansOfType(InboundEventStore::class.java).size shouldBe 1
            ctx.getBeansOfType(RecordedDocumentsByKey::class.java).size shouldBe 1
            (ctx.getBean(RecordedDocumentsByKey::class.java) is JooqRecordedDocumentsByKey) shouldBe true
            (ctx.getBean(DeclaredCreditNotes::class.java) is NettingLinksDeclaredCreditNotes) shouldBe true
            val lookupOf =
                NettingLinksDeclaredCreditNotes::class.java.getDeclaredField("lookup").apply { isAccessible = true }
            (
                lookupOf.get(ctx.getBean(DeclaredCreditNotes::class.java)) ===
                    ctx.getBean(RecordedDocumentsByKey::class.java)
            ) shouldBe
                true
        }
    }

    @Test
    fun `INV-AC-27994 B2 a DocumentRowWriter is constructible from the beans and the version writer`(
        @TempDir dir: Path,
    ) {
        CanonicalContext.online(dir).run { ctx ->
            val writer =
                DocumentRowWriter(
                    ctx.getBean(CanonicalVersionWriter::class.java),
                    ctx.getBean(NetRederivation::class.java),
                    ctx.getBean(DeclaredCreditNotes::class.java),
                    ctx.getBean(InboundEventStore::class.java),
                )
            DocumentRowWriter::class.java.isInstance(writer) shouldBe true
        }
    }

    private fun lastRow(
        sql: String,
        doc: UUID,
    ): String =
        CanonicalDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, doc)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $doc" }
                    rows.getString(1)
                }
            }
        }

    /** The canonical text of the stored derived version 4 of the document. */
    private fun storedCanonical(doc: UUID) =
        lastRow("select canonical::text from canonical.document_version where doc_uuid = ? and version = 4", doc)

    /** The data of the last CanonicalVersionCreated event staged for the document. */
    private fun stagedEvent(doc: UUID) =
        lastRow(
            "select data::text from canonical.outbox where doc_uuid = ? and type like '%CanonicalVersionCreated%' " +
                "order by seq desc limit 1",
            doc,
        )

    /** The request being handled, as the trace the outbox rows of a derived version are stamped from. */
    private fun envelope(tenant: UUID) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/submission-svc",
            type = "com.dynokore.gst.submission.NetDerivationRequested.v1",
            subject = "x",
            time = Instant.parse("2026-11-23T10:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E-001"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "submission-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    private class Doc(
        val tenant: UUID,
        val entity: UUID,
        val invoice: UUID,
        val note: UUID,
        val number: String,
        val amount: String,
    )

    private fun canonical(
        number: String,
        amount: String,
        tax: String,
        gross: String,
        preceding: String? = null,
    ) = """{"documentTypeCode":{"value":"380","classification":"NONE"},""" +
        """"currency":{"value":"SGD","classification":"NONE"},""" +
        """"documentNumber":{"value":"$number","classification":"NONE"},""" +
        preceding?.let { """"precedingDocumentNumber":{"value":"$it","classification":"NONE"},""" }.orEmpty() +
        """"parties":{"value":{"supplier":{"identifierScheme":"UEN","identifierValue":"201912345A"},""" +
        """"buyer":{"identifierScheme":"UEN","identifierValue":"201912345B"}},"classification":"IDENTIFYING"},""" +
        """"lines":{"value":[{"lineNumber":1,"description":"Consulting","quantity":1,"unitPrice":"$amount",""" +
        """"lineAmount":"$amount","taxCode":"SR","taxAmount":"$tax"}],"classification":"NONE"},""" +
        """"taxSubtotals":{"value":[{"taxCode":"SR","taxableAmount":"$amount","taxAmount":"$tax"}],""" +
        """"classification":"NONE"},""" +
        """"totals":{"value":{"totalExcludingTax":"$amount","totalTax":"$tax","totalIncludingTax":"$gross"},""" +
        """"classification":"NONE"}}"""

    private fun version(canonical: String) =
        NewVersion(
            canonical,
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            day,
            "svc:seed",
        )

    private fun newDocument(
        d: Doc,
        doc: UUID,
        number: String,
        canonical: String,
        periodId: UUID,
    ) = NewDocument(
        doc,
        d.tenant,
        d.entity,
        null,
        Direction.PURCHASE,
        380,
        number,
        "issuer-1",
        2026,
        null,
        DocumentState.READY,
        periodId,
        version(canonical),
    )

    private fun insertPeriod(
        dsl: DSLContext,
        tenant: UUID,
        periodId: UUID,
    ) {
        dsl
            .insertInto(PERIOD_SPAN)
            .set(PERIOD_SPAN.TENANT_ID, tenant)
            .set(PERIOD_SPAN.ENTITY_REF, "E-001")
            .set(PERIOD_SPAN.PERIOD_REF, "2026-Q1")
            .set(PERIOD_SPAN.PERIOD_START, LocalDate.of(2026, 1, 1))
            .set(PERIOD_SPAN.PERIOD_END, LocalDate.of(2026, 3, 31))
            .set(PERIOD_SPAN.PERIOD_ID, periodId)
            .execute()
    }

    private fun insertInbound(
        dsl: DSLContext,
        d: Doc,
        doc: UUID,
        function: String,
    ) {
        dsl
            .insertInto(INBOUND_EVENT)
            .set(INBOUND_EVENT.TENANT_ID, d.tenant)
            .set(INBOUND_EVENT.ENTITY_ID, d.entity)
            .set(INBOUND_EVENT.DOC_UUID, doc)
            .set(INBOUND_EVENT.VERSION, 1)
            .set(INBOUND_EVENT.EVENT_KIND, "POSTING")
            .set(INBOUND_EVENT.DOCUMENT_FUNCTION, function)
            .execute()
    }

    private fun insertDocuments(
        ctx: ApplicationContext,
        d: Doc,
    ) {
        val dsl = ctx.getBean(DSLContext::class.java)
        val store = ctx.getBean(DocumentStore::class.java)
        val periodId = UUID.randomUUID()
        insertPeriod(dsl, d.tenant, periodId)
        val invoice = canonical(d.number, d.amount, "810.00", "9810.00")
        store.insert(newDocument(d, d.invoice, d.number, invoice, periodId))
        val noteNumber = "CN-" + d.note
        val note = canonical(noteNumber, "1000.00", "90.00", "1090.00", d.number)
        store.insert(newDocument(d, d.note, noteNumber, note, periodId))
        insertInbound(dsl, d, d.invoice, "INVOICE")
        insertInbound(dsl, d, d.note, "CREDIT_NOTE")
    }

    /** A purchase invoice, a credit note naming it, and the derived net version 2 of the invoice. */
    private fun seed(
        ctx: ApplicationContext,
        amount: String,
    ): Doc {
        val tenant = UUID.randomUUID()
        val number = "INV-" + UUID.randomUUID()
        val d = Doc(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), number, amount)
        ctx.getBean(TenantScope::class.java).bind(TenantId(tenant), actor) {
            ctx.getBean(TransactionRunner::class.java).inTransaction {
                insertDocuments(ctx, d)
                // the system-derived net version 2 of the invoice, which makes the next ERP version re-derivable
                ctx.getBean(InboundEventTrace::class.java).within(envelope(tenant)) {
                    ctx
                        .getBean(NetDerivationRequestHandler::class.java)
                        .handle(UUID.randomUUID(), d.invoice, 1, listOf(NetDerivationCreditNoteFact(d.note, 1)))
                }
            }
        }
        return d
    }

    /** A new ERP version 3 of the invoice, then the re-derivation, in one transaction; [go] holds both threads. */
    private fun reDerive(
        ctx: ApplicationContext,
        d: Doc,
        ready: CountDownLatch,
        go: CountDownLatch,
    ) {
        ctx.getBean(TenantScope::class.java).bind(TenantId(d.tenant), actor) {
            ctx.getBean(TransactionRunner::class.java).inTransaction {
                val erp = version(canonical(d.number, d.amount, "810.00", "9810.00"))
                ctx.getBean(DocumentStore::class.java).createVersion(d.invoice, 2, erp)
                ready.countDown()
                go.await(10, TimeUnit.SECONDS)
                ctx.getBean(InboundEventTrace::class.java).within(envelope(d.tenant)) {
                    ctx.getBean(NetRederivation::class.java).afterErpVersion(d.invoice, 3, emptyList())
                }
            }
        }
    }

    private fun assertOwnText(pair: List<Doc>) {
        pair.forEach { d ->
            val other = pair.first { candidate -> candidate !== d }
            listOf(storedCanonical(d.invoice), stagedEvent(d.invoice)).forEach { text ->
                text.contains(d.number) shouldBe true
                text.contains(other.number) shouldBe false
            }
        }
    }

    @Test
    fun `INV-AC-27994 T1 two threads re-derive two documents at the same time and each keeps its own text`(
        @TempDir dir: Path,
    ) {
        CanonicalContext.online(dir).run { ctx ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(ROUNDS) { _ ->
                    val pair = listOf(seed(ctx, "9000.00"), seed(ctx, "5000.00"))
                    val ready = CountDownLatch(2)
                    val go = CountDownLatch(1)
                    val futures = pair.map { d -> pool.submit { reDerive(ctx, d, ready, go) } }
                    ready.await(10, TimeUnit.SECONDS)
                    go.countDown()
                    futures.forEach { it.get(30, TimeUnit.SECONDS) }
                    assertOwnText(pair)
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private companion object {
        const val ROUNDS = 8
    }
}
