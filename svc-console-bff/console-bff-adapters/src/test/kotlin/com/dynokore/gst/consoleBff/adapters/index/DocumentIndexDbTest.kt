package com.dynokore.gst.consoleBff.adapters.index

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DOCUMENT_INDEX
import com.dynokore.gst.consoleBff.application.index.DocumentIndexStore
import com.dynokore.gst.consoleBff.domain.index.DocumentIndexRow
import com.dynokore.gst.consoleBff.domain.index.DocumentSearchCriteria
import com.dynokore.gst.consoleBff.domain.index.IndexState
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The document index on a live PostgreSQL as the runtime role under a signed binding (INV-AC-26808), plus the payload
 * readers over the published samples (INV-AC-26807 facts).
 */
class DocumentIndexDbTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611211400__console_bff_document_index.sql")).readText()

    private val doc = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    /** A JSON text written with single quotes. */
    private fun q(text: String) = text.replace('\'', '"')

    /** [sample] with the single-quoted members [head] placed first. */
    private fun j(
        head: String,
        base: String,
    ) = base.replaceFirst("{", q(head))

    private fun <T> asTenant(
        tenant: UUID?,
        block: (org.jooq.DSLContext) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            if (tenant != null) SignedBindings.bind(c, tenant)
            val result = block(DSL.using(c, SQLDialect.POSTGRES))
            c.commit()
            result
        }

    private fun <T> catalog(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    @Suppress("LongParameterList") // a row fixture with one named default per column
    private fun row(
        tenant: UUID,
        entity: String,
        doc: UUID = UUID.randomUUID(),
        version: Long = 1,
        number: String? = "N-$entity",
        counterparty: String? = "Party $entity",
        uen: String? = null,
        amount: String? = "100.00",
        date: String? = "2026-03-20",
        categories: Set<String> = setOf("SR"),
        state: IndexState = IndexState.READY,
        ack: String? = null,
        error: String? = null,
    ) = DocumentIndexRow(
        tenantId = tenant.toString(),
        docUuid = doc.toString(),
        version = version,
        entityRef = entity,
        documentNumber = number,
        counterpartyName = counterparty,
        counterpartyUen = uen,
        amount = amount?.let { BigDecimal(it) },
        documentDate = date?.let { LocalDate.parse(it) },
        categoryCodes = categories,
        state = state,
        acknowledgementId = ack,
        errorCode = error,
        transactionDate = LocalDate.parse("2026-03-31"),
    )

    private fun write(
        tenant: UUID,
        vararg rows: DocumentIndexRow,
    ) = asTenant(tenant) { dsl -> rows.forEach { JooqDocumentIndexStore(dsl).upsert(it) } }

    @Suppress("LongParameterList") // the port arguments plus the binding tenant
    private fun search(
        tenant: UUID,
        entities: Set<String>,
        criteria: DocumentSearchCriteria = DocumentSearchCriteria(),
        limit: Int = 50,
        cursor: String? = null,
        asTenantId: UUID = tenant,
    ) = asTenant(asTenantId) { JooqDocumentIndexReads(it).search(tenant.toString(), entities, criteria, limit, cursor) }

    @Test
    fun `INV-AC-26808 the table has the columns, key, indexes, RLS section and least-privilege grants`() {
        val begin = "-- BEGIN TenantRlsMigration.ddl(\"console_bff\", \"document_index\")"
        val section = migration.substringAfter(begin, "").substringBefore("-- END TenantRlsMigration.ddl", "")
        section.isNotBlank() shouldBe true
        section.trim() shouldBe TenantRlsMigration.ddl("console_bff", "document_index").trim()
        catalog(
            "select column_name, data_type, is_nullable from information_schema.columns " +
                "where table_schema = 'console_bff' and table_name = 'document_index'",
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) } shouldContainExactlyInAnyOrder
            listOf(
                Triple("tenant_id", "uuid", "NO"),
                Triple("doc_uuid", "uuid", "NO"),
                Triple("version", "bigint", "NO"),
                Triple("entity_ref", "text", "NO"),
                Triple("document_number", "text", "YES"),
                Triple("counterparty_name", "text", "YES"),
                Triple("counterparty_uen", "text", "YES"),
                Triple("amount", "numeric", "YES"),
                Triple("document_date", "date", "YES"),
                Triple("transaction_date", "date", "NO"),
                Triple("category_codes", "ARRAY", "NO"),
                Triple("state", "text", "NO"),
                Triple("acknowledgement_id", "text", "YES"),
                Triple("error_code", "text", "YES"),
            )
        catalog(
            "select numeric_precision, numeric_scale from information_schema.columns " +
                "where table_name = 'document_index' and column_name = 'amount'",
        ) { it.getInt(1) to it.getInt(2) } shouldBe listOf(19 to 2)
    }

    @Test
    fun `INV-AC-26808 the key, the two indexes, the forced RLS and the grants are those of the design`() {
        catalog(
            "select a.attname from pg_index i join pg_attribute a " +
                "on a.attrelid = i.indrelid and a.attnum = any(i.indkey) " +
                "where i.indrelid = 'console_bff.document_index'::regclass and i.indisprimary order by a.attname",
        ) { it.getString(1) } shouldBe listOf("doc_uuid", "tenant_id", "version")
        catalog(
            "select indexdef from pg_indexes where schemaname = 'console_bff' and tablename = 'document_index' " +
                "and indexname <> 'document_index_pkey'",
        ) { it.getString(1).substringAfter("USING btree ") } shouldContainExactlyInAnyOrder
            listOf("(tenant_id, entity_ref, document_date)", "(tenant_id, acknowledgement_id)")
        catalog(
            "select relrowsecurity, relforcerowsecurity from pg_class " +
                "where oid = 'console_bff.document_index'::regclass",
        ) { it.getBoolean(1) to it.getBoolean(2) } shouldBe listOf(true to true)
        catalog(
            "select has_table_privilege('console_bff_runtime', 'console_bff.document_index', 'SELECT'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.document_index', 'INSERT'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.document_index', 'UPDATE'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.document_index', 'DELETE')",
        ) { listOf(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4)) } shouldBe
            listOf(listOf(true, true, true, false))
    }

    @Test
    fun `INV-AC-26808 the search returns only rows of the entity set whatever the criteria`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val e1 = row(t1, "E1", ack = "ACK-1", amount = "50.00")
        val e1other = row(t1, "E1", amount = "500.00", ack = "ACK-2")
        val e2 = row(t1, "E2")
        val e3 = row(t1, "E3", counterparty = "Only Three Pte Ltd")
        write(t1, e1, e1other, e2, e3)
        write(t2, row(t2, "E1", doc = UUID.fromString(e1.docUuid)))

        val first = search(t1, setOf("E1", "E2"))
        first.items.map { it.docUuid }.toSet() shouldBe setOf(e1.docUuid, e1other.docUuid, e2.docUuid)
        first.items.size shouldBe 3
        search(t1, setOf("E1", "E2"), DocumentSearchCriteria(counterparty = "Only Three")).items shouldBe emptyList()
        search(t1, emptySet()).items shouldBe emptyList()
        val fourth =
            search(
                t1,
                setOf("E1", "E2"),
                DocumentSearchCriteria(
                    acknowledgementId = "ACK-1",
                    amountFrom = BigDecimal("10.00"),
                    amountTo = BigDecimal("60.00"),
                ),
            )
        fourth.items.map { it.docUuid } shouldBe listOf(e1.docUuid)
        // the T2 statement asks for the rows of T1 by Doc UUID (the T1 tenant id in the statement, the T2 binding)
        search(t1, setOf("E1", "E2"), DocumentSearchCriteria(docUuid = e1.docUuid), asTenantId = t2).items shouldBe
            emptyList()
        // the row level security alone, without any tenant or entity predicate in the statement
        val sameDoc = UUID.fromString(e1.docUuid)
        asTenant(t2) { dsl ->
            dsl
                .selectFrom(DOCUMENT_INDEX)
                .where(DOCUMENT_INDEX.DOC_UUID.eq(sameDoc))
                .fetch()
                .map { it.tenantId }
        } shouldBe listOf(t2)
        asTenant(UUID.randomUUID()) { dsl -> dsl.selectFrom(DOCUMENT_INDEX).fetch() }.size shouldBe 0
    }

    @Test
    fun `INV-AC-26808 the statement of every search names the tenant and the entity set in its WHERE clause`() {
        val t = UUID.randomUUID()
        write(t, row(t, "E1"))
        val criteria =
            listOf(
                DocumentSearchCriteria(),
                DocumentSearchCriteria(entityRef = "E1", documentNumber = "N-E1", counterparty = "x"),
                DocumentSearchCriteria(
                    amountFrom = BigDecimal.ONE,
                    dateFrom = LocalDate.parse("2026-01-01"),
                    state = IndexState.READY,
                ),
                DocumentSearchCriteria(categoryCode = "SR", errorCode = "E", acknowledgementId = "A"),
                DocumentSearchCriteria(docUuid = UUID.randomUUID().toString()),
            )
        asTenant(t) { dsl ->
            val reads = JooqDocumentIndexReads(dsl)
            for (c in criteria) {
                val where =
                    checkNotNull(
                        reads.statement(t.toString(), setOf("E1"), c, 10, null),
                    ).sql.substringAfter(" where ")
                where shouldContain "\"entity_ref\" = any (cast(? as varchar[]))"
                where shouldContain "\"tenant_id\" = "
            }
            reads.statement(t.toString(), emptySet(), DocumentSearchCriteria(), 10, null) shouldBe null
        }
    }

    @Test
    fun `INV-AC-26808 a hostile criterion is a bound value and matches nothing`() {
        val t = UUID.randomUUID()
        write(t, row(t, "E1"))
        search(t, setOf("E1"), DocumentSearchCriteria(counterparty = "' or 1=1 --")).items shouldBe emptyList()
        search(t, setOf("E1"), DocumentSearchCriteria(documentNumber = "' or 1=1 --")).items shouldBe emptyList()
        search(t, setOf("E1"), DocumentSearchCriteria(counterparty = "100%")).items shouldBe emptyList()
        search(t, setOf("E1"), DocumentSearchCriteria(counterparty = "party e1")).items.size shouldBe 1
    }

    @Test
    fun `INV-AC-26808 a date range matches no null date and an unfiltered search orders null dates last`() {
        val t = UUID.randomUUID()
        val dated = row(t, "E1", date = "2026-03-20")
        val later = row(t, "E1", date = "2026-04-20")
        val undated = row(t, "E1", date = null)
        write(t, undated, later, dated)
        search(t, setOf("E1")).items.map { it.docUuid } shouldBe listOf(dated.docUuid, later.docUuid, undated.docUuid)
        val from = search(t, setOf("E1"), DocumentSearchCriteria(dateFrom = LocalDate.parse("2000-01-01")))
        from.items.map { it.docUuid } shouldBe listOf(dated.docUuid, later.docUuid)
        val to = search(t, setOf("E1"), DocumentSearchCriteria(dateTo = LocalDate.parse("2100-01-01")))
        to.items.map { it.docUuid } shouldBe listOf(dated.docUuid, later.docUuid)
        // the keyset cursor walks the null date without repeating a hit
        val p1 = search(t, setOf("E1"), limit = 2)
        p1.items.size shouldBe 2
        val p2 = search(t, setOf("E1"), limit = 2, cursor = p1.nextCursor)
        p2.items.map { it.docUuid } shouldBe listOf(undated.docUuid)
        p2.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-26808 the store applies state ranks, never lowers a row and counts what changed`() {
        val t = UUID.randomUUID()
        val r = row(t, "E1")
        write(t, r)
        asTenant(t) { dsl ->
            val s: DocumentIndexStore = JooqDocumentIndexStore(dsl)
            s.upsert(r.copy(state = IndexState.ACKNOWLEDGED)) shouldBe false
            s.updateDocument(t.toString(), r.docUuid) { it.copy(state = IndexState.HELD) } shouldBe 1
            s.updateDocument(t.toString(), r.docUuid) { it } shouldBe 0
            s.update(t.toString(), r.docUuid, 2) { it.copy(errorCode = "X") } shouldBe 0
            s.update(t.toString(), r.docUuid, 1) { it.copy(errorCode = "X") } shouldBe 1
        }
        search(t, setOf("E1")).items.single().state shouldBe IndexState.HELD
        search(t, setOf("E1"), DocumentSearchCriteria(errorCode = "X")).items.size shouldBe 1
        search(t, setOf("E1"), DocumentSearchCriteria(categoryCode = "SR")).items.size shouldBe 1
        search(t, setOf("E1"), DocumentSearchCriteria(categoryCode = "ZR")).items.size shouldBe 0
    }

    // ---- readers ----

    private val sample =
        """{"docUuid":"3fa85f64-5717-4562-b3fc-2c963f66afa6","version":1,"docsequence":1,"transaction""" +
            """Date":"2026-03-20","prescribedPeriodRef":"2026-Q1","scopeVerdict":"SUBMITTABLE","appliedPa""" +
            """ckVersions":[{"packIdentifier":"sg-gst","version":"1.0.0"}],"traceReference":"trace-0001",""" +
            """"canonicalDocument":{"documentTypeCode":{"value":"380","classification":"NONE"},"currency"""" +
            """:{"value":"SGD","classification":"NONE"},"parties":{"value":{"supplier":{"identifierScheme""" +
            """":"SG_UEN","identifierValue":"201912345A","name":"Acme Pte Ltd","gstn":"M2-1234567-8","add""" +
            """ress":{"line1":"1 Raffles Place","postalCode":"048616","countryCode":"SG"}},"buyer":{"iden""" +
            """tifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Acme Pte Ltd","gstn":"M2-123""" +
            """4567-8","address":{"line1":"1 Raffles Place","postalCode":"048616","countryCode":"SG"}}},"""" +
            """classification":"IDENTIFYING"},"lines":{"value":[{"lineNumber":1,"description":"Consulting""" +
            """","quantity":1,"unitPrice":"100.00","lineAmount":"100.00","taxCode":"SR","taxAmount":"9.00""" +
            """","unitCode":"EA"}],"classification":"NONE"},"taxSubtotals":{"value":[{"taxCode":"SR","tax""" +
            """ableAmount":"100.00","taxAmount":"9.00"}],"classification":"NONE"},"totals":{"value":{"tot""" +
            """alExcludingTax":"100.00","totalTax":"9.00","totalIncludingTax":"109.00"},"classification":""" +
            """"NONE"},"documentNumber":{"value":"INV-2026-000123","classification":"NONE"},"documentDate""" +
            """":{"value":"2026-03-20","classification":"NONE"},"invoiceNote":{"value":"Thanks","classifi""" +
            """cation":"NONE"},"identityTriple":{"value":{"senderId":"sssssssssssssssssssssssssssssssssss""" +
            """sssssssssssssssssssssssssssss","supplierId":"iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii""" +
            """iiiiiiiiiiiiiiiiiii","supplierGstn":"gggggggggggggggggggg"},"classification":"IDENTIFYING"""" +
            """}},"aggregateId":"AGG-2026-0001"}"""

    private val withEntity = j("{'entityRef':'E1',", sample)

    @Test
    fun `INV-AC-26807 the published version sample gives its facts and no entityRef gives null`() {
        val reader = JacksonCanonicalVersionCreatedReader()
        val none = reader.read(sample)
        none.entityRef shouldBe null
        val f = reader.read(withEntity)
        f.entityRef shouldBe "E1"
        f.docUuid shouldBe "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        f.version shouldBe 1
        f.documentNumber shouldBe "INV-2026-000123"
        f.amount shouldBe BigDecimal("109.00")
        f.categoryCodes shouldBe setOf("SR")
        f.scopeVerdict shouldBe "SUBMITTABLE"
        f.counterpartyName shouldBe null
        f.documentDate shouldBe LocalDate.parse("2026-03-20")
        f.transactionDate shouldBe LocalDate.parse("2026-03-20")
    }

    @Test
    fun `INV-AC-26807 the counterparty is the buyer for SALE and the supplier for PURCHASE`() {
        val reader = JacksonCanonicalVersionCreatedReader()
        val sale = reader.read(j("{'routingFacts':{'direction':'SALE'},", sample))
        sale.counterpartyUen shouldBe "201888888A"
        val purchase = reader.read(j("{'routingFacts':{'direction':'PURCHASE'},", sample))
        purchase.counterpartyUen shouldBe "201912345A"
        purchase.counterpartyName shouldBe "Acme Pte Ltd"
        val other = reader.read(j("{'routingFacts':{'direction':'SALE'},", sample).replace("\"SG_UEN\"", "\"OTHER\""))
        other.counterpartyUen shouldBe null
    }

    @Test
    fun `INV-AC-26807 the document date and the transaction date are two values, never one for the other`() {
        val reader = JacksonCanonicalVersionCreatedReader()
        val both = sample.replace("\"transactionDate\":\"2026-03-20\"", "\"transactionDate\":\"2026-03-31\"")
        reader.read(both).documentDate shouldBe LocalDate.parse("2026-03-20")
        reader.read(both).transactionDate shouldBe LocalDate.parse("2026-03-31")
        val noDate = both.replace(",\"documentDate\":{\"value\":\"2026-03-20\",\"classification\":\"NONE\"}", "")
        (noDate == both) shouldBe false
        reader.read(noDate).documentDate shouldBe null
        reader.read(noDate).transactionDate shouldBe LocalDate.parse("2026-03-31")
    }

    @Test
    fun `INV-AC-26807 a malformed version payload is a contract violation`() {
        val reader = JacksonCanonicalVersionCreatedReader()
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"docUuid\":\"$doc\",", "")) }
        shouldThrow<ContractViolation> { reader.read("[]") }
        shouldThrow<ContractViolation> { reader.read("{") }
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"transactionDate\":\"2026-03-20\",", "")) }
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"version\":1", "\"version\":0")) }
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"109.00\"", "\"abc\"")) }
    }

    @Test
    fun `INV-AC-26807 the despatched, acknowledged, failed and master data readers give their facts`() {
        val d = JacksonSubmissionDespatchedReader().read(q("{'docUuid':'$doc','version':2,'attemptNumber':1}"))
        (d.docUuid to d.version) shouldBe (doc to 2L)
        d.acknowledgementId shouldBe null
        val acked = JacksonSubmissionAcknowledgedReader()
        val a = acked.read(q("{'docUuid':'$doc','version':2,'acknowledgementId':'ACK-9'}"))
        a.acknowledgementId shouldBe "ACK-9"
        shouldThrow<ContractViolation> { acked.read(q("{'docUuid':'$doc','version':2}")) }
        shouldThrow<ContractViolation> { JacksonSubmissionDespatchedReader().read("{\"version\":2}") }
        JacksonSubmissionFailedIndexReader().read(q("{'docUuid':'$doc','version':2,'code':'E42'}")).code shouldBe "E42"
        JacksonSubmissionFailedIndexReader().read("{\"docUuid\":\"$doc\",\"version\":2}").code shouldBe null
        val m =
            JacksonMasterDataResolvedUenReader().read(
                q(
                    "{'partyRef':'p','docUuids':['$doc'],'resolvedIdentifiers':[{'scheme':'GST','value':'g'}," +
                        "{'scheme':'UEN','value':'201912345A'},{'scheme':'UEN','value':'second'}]}",
                ),
            )
        (m.docUuids to m.uen) shouldBe (listOf(doc) to "201912345A")
        JacksonMasterDataResolvedUenReader().read("{\"partyRef\":\"p\",\"resolvedIdentifiers\":[]}").let {
            it.docUuids shouldBe emptyList()
            it.uen shouldBe null
        }
    }
}
