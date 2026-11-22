package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexReader
import com.dynokore.gst.evidence.application.lookup.ReverseLookupFilter
import com.dynokore.gst.evidence.application.lookup.ReverseLookupRow
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-27668: the entity-scoped read of `evidence.submission_index` and the entity index (INV-REQ-710). */
class SubmissionIndexScopedReaderDbTest {
    private val tenant = UUID.randomUUID()

    private fun sql(vararg statements: String) =
        EvidenceDb.superuser().use { c -> c.createStatement().use { s -> statements.forEach { s.execute(it) } } }

    @Suppress("LongParameterList") // one column per argument, each defaulted
    private fun put(
        entity: String?,
        tenantId: UUID = tenant,
        doc: UUID = UUID.randomUUID(),
        date: String = "2026-01-15",
        number: String = "INV-1",
        counterparty: String = "201912345A",
        period: String = "2026-Q1",
        codes: List<String> = emptyList(),
    ): UUID {
        val e = entity?.let { "'$it'" } ?: "null"
        sql(
            "insert into evidence.submission_index (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                "document_number, document_date, counterparty_uen, state, recorded_at) values " +
                "('$tenantId', '$doc', 1, $e, '$period', '$number', '$date', '$counterparty', 'SENT', now())",
            *codes
                .map { "insert into evidence.submission_index_category values ('$tenantId', '$doc', 1, '$it')" }
                .toTypedArray(),
        )
        return doc
    }

    private fun scoped(
        entities: Set<String>,
        filter: ReverseLookupFilter = ReverseLookupFilter(),
        tenantId: UUID = tenant,
    ): List<ReverseLookupRow> =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenantId)
            JooqSubmissionIndexReader(DSL.using(c, SQLDialect.POSTGRES))
                .readScoped(TenantId(tenantId), entities, filter, null, 100)
                .also { c.commit() }
        }

    private fun docs(
        entities: Set<String>,
        filter: ReverseLookupFilter = ReverseLookupFilter(),
    ) = scoped(entities, filter).map { it.key.docUuid }.toSet()

    @Test
    fun `INV-AC-27668 case 1 a scope of E1 and E2 returns their documents and none of E3`() {
        val e1 = put("E1")
        val e2 = put("E2")
        put("E3")
        docs(setOf("E1", "E2"), ReverseLookupFilter(counterparty = "201912345A")) shouldBe setOf(e1, e2)
    }

    @Test
    fun `INV-AC-27668 case 2 a row with null entity_ref is not returned`() {
        val e1 = put("E1")
        put(null)
        docs(setOf("E1")) shouldBe setOf(e1)
    }

    @Test
    fun `INV-AC-27668 case 3 the same entity in another tenant is not returned`() {
        val other = UUID.randomUUID()
        val mine = put("E1")
        val theirs = put("E1", tenantId = other)
        docs(setOf("E1")) shouldBe setOf(mine)
        scoped(setOf("E1"), tenantId = other).map { it.key.docUuid } shouldBe listOf(theirs)
    }

    @Test
    fun `INV-AC-27668 case 4 look-alike entity references with a trailing space or lower case are not returned`() {
        val e1 = put("E1")
        put("E1 ")
        put("e1")
        put("E10")
        docs(setOf("E1")) shouldBe setOf(e1)
    }

    @Test
    fun `INV-AC-27668 case 5 an empty set returns an empty list and records zero statements`() {
        put("E1")
        val statements = AtomicInteger()
        val mock = MockConnection { _ -> statements.incrementAndGet().let { emptyArray<MockResult>() } }
        val reader = JooqSubmissionIndexReader(DSL.using(mock, SQLDialect.POSTGRES))
        reader.readScoped(TenantId(tenant), emptySet(), ReverseLookupFilter(), null, 10) shouldBe emptyList()
        statements.get() shouldBe 0
        scoped(emptySet()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27668 case 6 the other filters combine by AND under a scope and the order matches read`() {
        val a = put("E1", date = "2026-01-10", number = "N-A", period = "P1", codes = listOf("SR"))
        val b = put("E2", date = "2026-02-10", number = "N-B", period = "P1", codes = listOf("SR"))
        put("E1", date = "2026-03-10", number = "N-C", period = "P2", codes = listOf("ZR"))
        put("E1", date = "2026-01-12", number = "N-A", period = "P1", codes = listOf("SR"), counterparty = "X")
        val all = setOf("E1", "E2")
        docs(all, ReverseLookupFilter(counterparty = "201912345A", periodRef = "P1")) shouldBe setOf(a, b)
        docs(all, ReverseLookupFilter(documentNumber = "N-A", counterparty = "201912345A")) shouldBe setOf(a)
        docs(all, ReverseLookupFilter(dateFrom = LocalDate.of(2026, 2, 1), categoryCode = "SR")) shouldBe setOf(b)
        docs(all, ReverseLookupFilter(categoryCode = "SR", periodRef = "P2")) shouldBe emptySet()
        val ordered = scoped(all).map { it.key }
        ordered shouldBe ordered.sortedWith(compareBy({ it.documentDate }, { it.docUuid }, { it.version }))
        val unscoped =
            EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, tenant)
                JooqSubmissionIndexReader(DSL.using(c, SQLDialect.POSTGRES))
                    .read(TenantId(tenant), ReverseLookupFilter(), null, 100)
                    .also { c.commit() }
            }
        unscoped.map { it.key }.filter { k -> scoped(all).any { it.key == k } } shouldBe ordered
    }

    @Test
    fun `INV-AC-27668 case 7 the entity index lists tenant_id entity_ref document_number in order`() {
        val columns =
            EvidenceDb.superuser().use { c ->
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "select indexdef from pg_indexes where schemaname = 'evidence' and " +
                                "tablename = 'submission_index' and " +
                                "indexname = 'submission_index_entity_document_number'",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }
        columns.size shouldBe 1
        columns.single().substringAfter("(").substringBefore(")") shouldBe "tenant_id, entity_ref, document_number"
    }
}
