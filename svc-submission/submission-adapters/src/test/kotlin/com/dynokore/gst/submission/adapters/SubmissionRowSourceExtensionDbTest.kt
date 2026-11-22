package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionRowSource
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The list row source fills the original and extended due date only where an extension row applies (INV-AC-20729). */
class SubmissionRowSourceExtensionDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return block().also { connection.commit() }
            }
        }

    private val submissions = JooqSubmissionRowSource(dsl, runner)
    private val register = LocalDate.of(2026, 4, 30)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    private fun seedDoc(
        tenant: UUID,
        doc: String,
        entity: String,
        period: String,
        tx: LocalDate,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.obligation_register_entry (tenant_id, doc_uuid, entity_ref, period_ref, " +
                "due_date, " +
                "in_scope, kind, transaction_date, document_number, document_type_code, direction, " +
                "counterparty_name, " +
                "counterparty_uen, taxable_amount, gst_amount, category_codes) " +
                "values (?, ?, ?, ?, ?, true, 'INVOICE', ?, 'N-1', '380', 'SALE', 'Acme', '201912345A', " +
                "100.00, 9.00, array['SR'])",
            tenant,
            doc,
            entity,
            period,
            register,
            tx,
        )
        dsl.execute(
            "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, transaction_date, " +
                "submission_type) values (?, ?, 1, 'READY', ?, ?, 'TYPE_1A')",
            tenant,
            doc,
            entity,
            tx,
        )
    }

    private class Ext(
        val target: String,
        val kind: String,
        val ref: String?,
        val original: String?,
        val extended: String,
    )

    private fun seedExtension(
        tenant: UUID,
        key: Pair<String, String>,
        ext: Ext,
    ) = asTenant(tenant) {
        dsl.execute(
            "insert into submission.due_date_extension (tenant_id, entity_ref, period_ref, target, scope_kind, " +
                "scope_ref, original_due_date, extended_due_date, iras_reference, source_event_id) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, 'IRAS-1', ?)",
            tenant,
            key.first,
            key.second,
            ext.target,
            ext.kind,
            ext.ref,
            ext.original?.let { LocalDate.parse(it) },
            LocalDate.parse(ext.extended),
            UUID.randomUUID().toString(),
        )
    }

    private data class Dates(
        val due: LocalDate,
        val original: LocalDate?,
        val extended: LocalDate?,
    )

    private fun read(
        tenant: UUID,
        entity: String,
        period: String,
    ): Map<String, Dates> {
        bound.set(tenant)
        try {
            return submissions.rows(TenantId(tenant), entity, period).rows.associate {
                it.docUuid to Dates(it.dueDate, it.originalDueDate, it.extendedDueDate)
            }
        } finally {
            bound.remove()
        }
    }

    private class Fixture(
        val a: UUID,
        val b: UUID,
        val s: String,
    ) {
        val entity = "e-$s"
        val other = "o-$s"
        val p = "p-$s"
        val q = "q-$s"
        val r = "r-$s"
        val d1 = "d1-$s"
        val d2 = "d2-$s"
        val d3 = "d3-$s"
        val d4 = "d4-$s"
    }

    private fun fixture(): Fixture {
        val f = Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString().take(8))
        seedDoc(f.a, f.d1, f.entity, f.p, LocalDate.of(2026, 3, 10))
        seedDoc(f.a, f.d2, f.entity, f.p, LocalDate.of(2026, 3, 20))
        seedDoc(f.a, f.d3, f.entity, f.q, LocalDate.of(2026, 3, 20))
        seedDoc(f.a, f.d4, f.entity, f.r, LocalDate.of(2026, 4, 2))
        val sub = "SUBMISSION_DUE_DATE"
        seedExtension(f.a, f.entity to f.p, Ext(sub, "PERIOD", null, "2026-04-30", "2026-05-14"))
        seedExtension(f.a, f.entity to f.q, Ext(sub, "DATE_RANGE", "2026-03-15/2026-03-31", null, "2026-06-01"))
        seedExtension(f.a, f.entity to f.r, Ext("FILING_DUE_DATE", "PERIOD", null, "2026-04-30", "2026-05-31"))
        // The second tenant extends the same entity and period; its rows are never applied to tenant A.
        seedDoc(f.b, "b1-${f.s}", f.entity, f.p, LocalDate.of(2026, 3, 20))
        seedExtension(f.b, f.entity to f.p, Ext(sub, "PERIOD", null, "2026-04-30", "2027-01-01"))
        return f
    }

    private val d0514 = LocalDate.of(2026, 5, 14)

    @Test
    fun `INV-AC-20729 a PERIOD row fills both dates and dueDate is the extended date`() {
        val f = fixture()
        val expected = Dates(d0514, register, d0514)
        read(f.a, f.entity, f.p) shouldBe mapOf(f.d1 to expected, f.d2 to expected)
    }

    @Test
    fun `INV-AC-20729 a DATE_RANGE row fills the extended date only`() {
        val f = fixture()
        val june = LocalDate.of(2026, 6, 1)
        read(f.a, f.entity, f.q) shouldBe mapOf(f.d3 to Dates(june, null, june))
    }

    @Test
    fun `INV-AC-20729 a document no row applies to and a FILING_DUE_DATE row leave both members null`() {
        val f = fixture()
        read(f.a, f.entity, f.r) shouldBe mapOf(f.d4 to Dates(register, null, null))
    }

    @Test
    fun `INV-AC-20729 a second tenant's extension row is never applied`() {
        val f = fixture()
        read(f.b, f.entity, f.p) shouldBe
            mapOf("b1-${f.s}" to Dates(LocalDate.of(2027, 1, 1), register, LocalDate.of(2027, 1, 1)))
        read(f.a, f.entity, f.p)[f.d1] shouldBe Dates(d0514, register, d0514)
    }

    @Test
    fun `INV-AC-20729 a document of another entity in the same period name is not affected`() {
        // doc_uuid is the register primary key, so the same Doc UUID cannot be stored in two entities; a different document is the reachable look-alike.
        val f = fixture()
        seedDoc(f.a, "d5-${f.s}", f.other, f.p, LocalDate.of(2026, 3, 10))
        read(f.a, f.other, f.p) shouldBe mapOf("d5-${f.s}" to Dates(register, null, null))
        read(f.a, f.entity, f.p)[f.d1] shouldBe Dates(d0514, register, d0514)
    }
}
