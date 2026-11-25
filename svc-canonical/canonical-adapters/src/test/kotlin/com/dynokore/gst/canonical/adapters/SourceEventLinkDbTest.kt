package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.events.JacksonAggregateDocumentJson
import com.dynokore.gst.canonical.adapters.events.JacksonIdentityTripleDocuments
import com.dynokore.gst.canonical.adapters.events.OutboxCanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqCurrentDocumentVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentAggregateRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqIdentityTripleRequestMarkers
import com.dynokore.gst.canonical.adapters.persistence.JooqInboundEventStore
import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PERIOD_SPAN
import com.dynokore.gst.canonical.application.aggregate.AggregateInput
import com.dynokore.gst.canonical.application.aggregate.AggregateLine
import com.dynokore.gst.canonical.application.aggregate.Classified
import com.dynokore.gst.canonical.application.aggregate.RecordAggregateDocument
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.AppliedPackFact
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.CounterpartyDirectory
import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.application.document.DocumentPartyRefs
import com.dynokore.gst.canonical.application.document.DocumentRowFacts
import com.dynokore.gst.canonical.application.document.DocumentRowWriter
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.PartyRefAnswer
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.document.RecordDocumentPipeline
import com.dynokore.gst.canonical.application.document.RecordDocumentService
import com.dynokore.gst.canonical.application.document.RecordPartyRef
import com.dynokore.gst.canonical.application.document.RowWritingResultBuilder
import com.dynokore.gst.canonical.application.document.SourceLink
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleReprocessing
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-28870 on PostgreSQL: every document version stores the key of the `source_event` row of the event that
 * produced it. The real use cases run over `JooqDocumentStore` and `JooqSourceEventStore` as the runtime login; the
 * three columns of every written version are compared by equality with the expected key.
 */
class SourceEventLinkDbTest : NetDerivationHarness() {
    @Test
    fun `INV-AC-28870 case a a record and a higher revision each name their own four-part key`() {
        val s = scope()
        val number = "A-" + s.invoice

        record(s, s.invoice, "k-a", 1, number, invoice(number))
        record(s, s.invoice, "k-a", 2, number, invoice(number))

        links(s.invoice) shouldBe listOf(link(1, ERP, "k-a", 1), link(2, ERP, "k-a", 2))
    }

    @Test
    fun `INV-AC-28870 case b a record that writes no row is named by no version, also at the same number`() {
        val s = scope()
        val number = "B-" + s.invoice

        record(s, s.invoice, "k-b", 1, number, invoice(number))
        record(s, s.invoice, "k-b", 2, number, canonical = null) // held: no facts, no row
        links(s.invoice) shouldBe listOf(link(1, ERP, "k-b", 1))
        record(s, s.invoice, "k-b", 3, number, invoice(number))

        links(s.invoice) shouldBe listOf(link(1, ERP, "k-b", 1), link(2, ERP, "k-b", 3))
        events(s.tenant) shouldBe 3
        DocumentSql.one(
            "select count(*)::text from canonical.document_version where tenant_id = ? and source_key = 'k-b' " +
                "and source_revision = 2",
            s.tenant,
        ) shouldBe "0"
    }

    @Test
    fun `INV-AC-28870 case c a re-derived version names the ERP revision that produced it, not the predecessor`() {
        val s = scope()
        val number = invoiceAndCreditNote(s)
        val request = UUID.randomUUID()
        handle(s, request, base = 1)

        record(s, s.invoice, "k-inv", 2, number, invoice(number))

        links(s.invoice) shouldBe
            listOf(
                link(1, ERP, "k-inv", 1),
                link(2, "submission-svc", "$request/${s.invoice}", 1),
                link(3, ERP, "k-inv", 2),
                link(4, ERP, "k-inv", 2),
            )
        recordRows(s) shouldBe 2
    }

    @Test
    fun `INV-AC-28870 case d an aggregate version names the aggregation-svc key of the aggregate`() {
        val s = scope()
        val aggregateId = "AGG-" + s.invoice
        val useCase =
            RecordAggregateDocument(
                RecordSourceEvent(JooqSourceEventStore(dsl), transaction) { s.invoice },
                { _, _, _, _ ->
                    Classified("SR", "IN_SCOPE", mapOf("SCOPE" to "3", "MAPPING" to "5"), BigDecimal("9.00"))
                },
                { _, _ -> s.entity },
                { _, _, _ -> SupplierAnswer.Found(OLD_TRIPLE) },
                JacksonAggregateDocumentJson(),
                writer(),
            )

        bound(s.tenant) {
            useCase.record(
                AggregateInput(
                    s.tenant,
                    aggregateId,
                    ENTITY_REF,
                    "POS",
                    "2026-Q1",
                    DAY,
                    false,
                    listOf(AggregateLine("S1", BigDecimal("100.00"))),
                ),
            )
        }

        links(s.invoice) shouldBe listOf(link(1, "aggregation-svc", aggregateId, 1))
        eventRows(s.tenant) shouldBe listOf(listOf("aggregation-svc", aggregateId, "1", s.invoice.toString()))
    }

    @Test
    fun `INV-AC-28870 case e a net derivation request writes its own source_event row, a refused one none`() {
        val s = scope()
        invoiceAndCreditNote(s)
        val request = UUID.randomUUID()
        val before = events(s.tenant)

        handle(s, request, base = 1)

        val composed = "$request/${s.invoice}"
        links(s.invoice) shouldBe listOf(link(1, ERP, "k-inv", 1), link(2, "submission-svc", composed, 1))
        events(s.tenant) shouldBe before + 1
        DocumentSql.rows(
            "select doc_uuid::text, (result = jsonb_build_object('docUuid', doc_uuid, 'version', 2))::text " +
                "from canonical.source_event where tenant_id = ? and source_system = 'submission-svc' " +
                "and source_key = ? and source_revision = 1",
            s.tenant,
            composed,
        ) shouldBe listOf(listOf(s.invoice.toString(), "true"))

        handle(s, UUID.randomUUID(), base = 1) // BASE_CHANGED: refused

        versionCount(s) shouldBe 2
        events(s.tenant) shouldBe before + 1
    }

    @Test
    fun `INV-AC-28870 case f one identity-triple request gives one row and link per written version only`() {
        val s = scope()
        val first = s.invoice
        val second = UUID.randomUUID()
        val unchanged = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        record(s, first, "k-f1", 1, "F1-$first", withTriple(invoice("F1-$first"), OLD_TRIPLE))
        record(s, second, "k-f2", 1, "F2-$second", withTriple(invoice("F2-$second"), OLD_TRIPLE))
        record(s, unchanged, "k-f3", 1, "F3-$unchanged", withTriple(invoice("F3-$unchanged"), NEW_TRIPLE))
        val before = events(s.tenant)
        val eventId = "identity-" + UUID.randomUUID()

        bound(s.tenant) {
            reprocessing().handle(s.tenant, ENTITY_REF, listOf(first, unknown, unchanged, second), eventId)
        }

        links(first) shouldBe listOf(link(1, ERP, "k-f1", 1), link(2, "submission-svc", "$eventId/$first", 1))
        links(second) shouldBe listOf(link(1, ERP, "k-f2", 1), link(2, "submission-svc", "$eventId/$second", 1))
        links(unchanged) shouldBe listOf(link(1, ERP, "k-f3", 1))
        links(unknown) shouldBe emptyList()
        events(s.tenant) shouldBe before + 2
        eventRows(s.tenant).filter { it[0] == "submission-svc" }.toSet() shouldBe
            setOf(first, second)
                .map { listOf("submission-svc", "$eventId/$it", "1", it.toString()) }
                .toSet()

        // a stale expected version in a transaction that commits: a conflict, and no source_event row either
        val outcome =
            inTenantResult(s.tenant) {
                store.createVersion(first, 1, version(invoice("F1-$first")), consumed("stale-" + UUID.randomUUID()))
            }

        outcome shouldBe UpdateOutcome.Conflict
        events(s.tenant) shouldBe before + 2
        links(first).size shouldBe 2
    }

    @Test
    fun `INV-AC-28870 case g the three columns are not null`() {
        DocumentSql.rows(
            "select column_name, is_nullable from information_schema.columns where table_schema = 'canonical' " +
                "and table_name = 'document_version' and column_name in " +
                "('source_system', 'source_key', 'source_revision') order by column_name",
        ) shouldBe listOf(listOf("source_key", "NO"), listOf("source_revision", "NO"), listOf("source_system", "NO"))
    }

    @Test
    fun `INV-AC-28870 case g a version whose key has no source_event row of its tenant fails at commit`() {
        val s = scope()
        val other = UUID.randomUUID()
        val missing = SourceKey(s.tenant, ERP, "missing-" + s.invoice, 1)
        val elsewhere = SourceKey(other, ERP, "other-tenant-" + s.invoice, 1)
        SourceLinks.seed(elsewhere, s.invoice)
        val document = document(s, s.invoice, "G-" + s.invoice, invoice("G-" + s.invoice))

        listOf(missing, elsewhere.copy(tenantId = s.tenant)).forEach { key ->
            var stored = false
            val failure =
                shouldThrow<Exception> {
                    inTenant(s.tenant) {
                        store.insert(document, SourceLink.Recorded(key))
                        stored = true // the write itself passes: the key is checked at commit
                    }
                }
            stored shouldBe true
            DocumentSql.sqlState(failure) shouldBe FOREIGN_KEY_VIOLATION
            links(s.invoice) shouldBe emptyList()
            documentRows(s.invoice) shouldBe "0"
        }
    }

    @Test
    fun `INV-AC-28870 case g a next version with an unknown key fails at commit and the pointer stays`() {
        val s = scope()
        val number = "G2-" + s.invoice
        record(s, s.invoice, "k-g", 1, number, invoice(number))

        val failure =
            shouldThrow<Exception> {
                inTenant(s.tenant) {
                    store.createVersion(
                        s.invoice,
                        1,
                        version(invoice(number)),
                        SourceLink.Recorded(SourceKey(s.tenant, ERP, "k-g", 2)),
                    )
                }
            }

        DocumentSql.sqlState(failure) shouldBe FOREIGN_KEY_VIOLATION
        links(s.invoice) shouldBe listOf(link(1, ERP, "k-g", 1))
        DocumentSql.documentColumn("current_version", s.invoice) shouldBe "1"
    }

    @Test
    fun `INV-AC-28870 case h a consumed-event key that exists fails with no version and no pointer move`() {
        val s = scope()
        invoiceAndCreditNote(s)
        val request = UUID.randomUUID()
        SourceLinks.seed(SourceKey(s.tenant, "submission-svc", "$request/${s.invoice}", 1), s.invoice)
        val before = events(s.tenant)

        shouldThrow<IllegalStateException> { handle(s, request, base = 1) }

        links(s.invoice) shouldBe listOf(link(1, ERP, "k-inv", 1))
        DocumentSql.documentColumn("current_version", s.invoice) shouldBe "1"
        events(s.tenant) shouldBe before
        recordRows(s) shouldBe 0
        DocumentSql.one(
            "select count(*)::text from canonical.processed_net_derivation_request where request_event_id = ?",
            request,
        ) shouldBe "0"
    }

    @Test
    fun `INV-AC-28870 case h a composed key of 128 characters is accepted and one of 129 writes nothing`() {
        val s = scope()
        val number = "H-" + s.invoice
        record(s, s.invoice, "k-h", 1, number, invoice(number))
        val versions = CreateDocumentVersion(store, transaction)
        val prefix = UUID.randomUUID().toString()
        val longest = prefix + "e".repeat(MAX_KEY - UUID_LENGTH - 1 - prefix.length)
        val tooLong = longest + "e"
        val before = events(s.tenant)

        shouldThrow<IllegalStateException> {
            bound(s.tenant) { versions.createVersion(s.invoice, 1, version(invoice(number)), consumed(tooLong)) }
        }

        links(s.invoice) shouldBe listOf(link(1, ERP, "k-h", 1))
        DocumentSql.documentColumn("current_version", s.invoice) shouldBe "1"
        events(s.tenant) shouldBe before

        bound(s.tenant) { versions.createVersion(s.invoice, 1, version(invoice(number)), consumed(longest)) } shouldBe
            UpdateOutcome.Updated(2)
        "$longest/${s.invoice}".length shouldBe MAX_KEY
        links(s.invoice).last() shouldBe link(2, "submission-svc", "$longest/${s.invoice}", 1)
        events(s.tenant) shouldBe before + 1
    }

    // ---- the use cases over the real stores ----

    /** A tenant with the period of its entity and no document yet. */
    private fun scope(): Seed =
        Seed().also { s ->
            inTenant(s.tenant) {
                dsl
                    .insertInto(PERIOD_SPAN)
                    .set(PERIOD_SPAN.TENANT_ID, s.tenant)
                    .set(PERIOD_SPAN.ENTITY_REF, ENTITY_REF)
                    .set(PERIOD_SPAN.PERIOD_REF, "2026-Q1")
                    .set(PERIOD_SPAN.PERIOD_START, LocalDate.of(2026, 1, 1))
                    .set(PERIOD_SPAN.PERIOD_END, LocalDate.of(2026, 3, 31))
                    .set(PERIOD_SPAN.PERIOD_ID, s.periodId)
                    .execute()
            }
        }

    private fun writer() =
        CanonicalVersionWriter(
            store,
            OutboxCanonicalVersionCreatedPublisher(outbox, tenants, corr, cause),
            JooqDocumentAggregateRefs(dsl, tenants),
        )

    /** One `recordDocument` call; a null [canonical] is a record that writes no row. */
    @Suppress("LongParameterList") // the values of one record that differ between the cases
    private fun record(
        s: Seed,
        doc: UUID,
        sourceKey: String,
        revision: Long,
        number: String,
        canonical: String?,
        function: DocumentFunction = DocumentFunction.INVOICE,
    ) {
        val command =
            RecordDocumentCommand(
                s.tenant,
                ENTITY_REF,
                ERP,
                sourceKey,
                revision,
                DAY,
                Direction.PURCHASE,
                PartyIdentifier("UEN", "201912345A"),
                PartyIdentifier("UEN", "201912345B"),
                true,
                documentNumber = number,
                documentFunction = function,
            )
        bound(s.tenant) { service(doc, builder(s, canonical)).record(command) }
    }

    /** The row-writing builder of the record path over the real stores. */
    private fun builder(
        s: Seed,
        canonical: String?,
    ): RowWritingResultBuilder {
        val writer = writer()
        val rows =
            DocumentRowWriter(
                writer,
                NetRederivation(records, documents, versions, writer) { id, net -> facts(id, net) },
                object : DeclaredCreditNotes {
                    override fun of(
                        command: RecordDocumentCommand,
                        entityId: UUID,
                    ): List<UUID> = emptyList()
                },
                JooqInboundEventStore(dsl, tenants),
            )
        return RowWritingResultBuilder(
            { _, _ -> """{"recorded":true}""" },
            JooqDocumentVersions(dsl),
            { _, _, _, _ -> canonical?.let { rowFacts(s, it) } },
            rows,
        )
    }

    private fun service(
        doc: UUID,
        builder: RowWritingResultBuilder,
    ): RecordDocumentService =
        RecordDocumentService(
            RecordDocumentPipeline(
                object : CounterpartyDirectory {
                    override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer = PartyRefAnswer.NotFound
                },
                RecordSourceEvent(JooqSourceEventStore(dsl), transaction) { doc },
                RecordPartyRef(
                    object : DocumentPartyRefs {
                        override fun upsert(
                            docUuid: UUID,
                            partyRef: String,
                        ) = error("no party reference is recorded here")
                    },
                ),
            ),
            object : EntityConstitutions {
                override fun constitutionOf(
                    tenantId: UUID,
                    entityRef: String,
                ): String? = null
            },
            builder,
        )

    private fun rowFacts(
        s: Seed,
        canonical: String,
    ) = DocumentRowFacts(
        s.entity,
        null,
        380,
        "issuer-1",
        2026,
        null,
        s.periodId,
        DocumentState.READY,
        "2026-Q1",
        listOf(AppliedPackFact("SCOPE", "SCOPE", "scope-1"), AppliedPackFact("MAPPING", "MAPPING", "map-1")),
        version(canonical),
    )

    /** Records a 9000.00 invoice (key `k-inv`) and a 1000.00 credit note that names it; returns its number. */
    private fun invoiceAndCreditNote(s: Seed): String {
        val number = "INV-" + s.invoice
        record(s, s.invoice, "k-inv", 1, number, invoice(number))
        val noteNumber = "CN-" + s.note
        val note = canonical(noteNumber, "1000.00", "90.00", "1090.00", number)
        record(s, s.note, "k-note", 1, noteNumber, note, DocumentFunction.CREDIT_NOTE)
        return number
    }

    private fun invoice(number: String) = canonical(number, "9000.00", "810.00", "9810.00")

    private fun withTriple(
        canonical: String,
        triple: SupplierIdentity,
    ) = """{"identityTriple":{"value":{"senderId":"${triple.senderId}","supplierId":"${triple.supplierId}",""" +
        """"supplierGstn":"${triple.supplierGstn}"},"classification":"IDENTIFYING"},""" + canonical.drop(1)

    private fun reprocessing() =
        IdentityTripleReprocessing(
            transaction,
            JooqIdentityTripleRequestMarkers(dsl, Clock.systemUTC()),
            JooqCurrentDocumentVersions(dsl, tenants),
            JacksonIdentityTripleDocuments(),
            { _, _, _ -> SupplierAnswer.Found(NEW_TRIPLE) },
            CreateDocumentVersion(store, transaction),
            OutboxCanonicalVersionCreatedPublisher(outbox, tenants, corr, cause),
            "svc:reprocessing",
            {},
        )

    private fun consumed(eventId: String) = SourceLink.Consumed("submission-svc", eventId)

    private fun <T> bound(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), actor, block)

    private fun <T> inTenantResult(
        tenant: UUID,
        block: () -> T,
    ): T = bound(tenant) { transaction.inTransaction(block) }

    // ---- reads ----

    private fun link(
        version: Int,
        system: String,
        key: String,
        revision: Long,
    ) = listOf(version.toString(), system, key, revision.toString())

    /** The version number and the three source columns of every version of [doc], by version. */
    private fun links(doc: UUID) =
        DocumentSql.rows(
            "select version::text, source_system, source_key, source_revision::text " +
                "from canonical.document_version where doc_uuid = ? order by version",
            doc,
        )

    private fun documentRows(doc: UUID) =
        DocumentSql.one("select count(*)::text from canonical.document where doc_uuid = ?", doc)

    private fun events(tenant: UUID) = eventRows(tenant).size

    private fun eventRows(tenant: UUID) =
        DocumentSql.rows(
            "select source_system, source_key, source_revision::text, doc_uuid::text " +
                "from canonical.source_event where tenant_id = ?",
            tenant,
        )

    private companion object {
        const val ERP = "erp-link"
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val MAX_KEY = 128
        const val UUID_LENGTH = 36
        val OLD_TRIPLE = SupplierIdentity("0195:201912345A", "0195:201912345A", "201912345A")
        val NEW_TRIPLE = SupplierIdentity("0195:201999999Z", "0195:201999999Z", "201999999Z")
    }
}
