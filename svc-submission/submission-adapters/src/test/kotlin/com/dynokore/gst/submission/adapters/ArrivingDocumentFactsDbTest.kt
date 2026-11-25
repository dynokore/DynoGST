package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqArrivingDocumentFacts
import com.dynokore.gst.submission.application.arrival.ArrivingDocument
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.Result
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The arriving-document facts read against `submission` as `submission_runtime` (INV-AC-28666, INV-REQ-1110). */
class ArrivingDocumentFactsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val reader = JooqArrivingDocumentFacts(dsl)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val suffix = UUID.randomUUID().toString()
    private val d0 = "D0-$suffix"
    private val d1 = "D1-$suffix"
    private val d2 = "D2-$suffix"
    private val d3 = "D3-$suffix"
    private val d4 = "D4-$suffix"
    private val d5 = "D5-$suffix"
    private val d6 = "D6-$suffix"
    private val d7 = "D7-$suffix"
    private val date = LocalDate.of(2026, 2, 27)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            listOf(t1, t2).forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.submission where tenant_id = '$t'")
                    it.execute("delete from submission.obligation_register_entry where tenant_id = '$t'")
                }
            }
        }
    }

    private val listFacts = "'380', 'SALE', 'Acme', '201912345A', 1.00, 0.09, array['TX'])"

    private class Reg(
        val doc: String,
        val kind: String,
        val number: String?,
        val txDate: LocalDate?,
        val preceding: String? = null,
    )

    private fun entry(
        tenant: UUID,
        reg: Reg,
    ) = SubmissionDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into submission.obligation_register_entry " +
                    "(tenant_id, doc_uuid, entity_ref, period_ref, due_date, in_scope, kind, document_number, " +
                    "transaction_date, preceding_doc_uuid, document_type_code, direction, counterparty_name, " +
                    "counterparty_uen, taxable_amount, gst_amount, category_codes) " +
                    "values (?, ?, 'E-1', 'P', ?, true, ?, ?, ?, ?, " +
                    (if (reg.number == null) "null, null, null, null, null, null, null)" else listFacts),
            ).use {
                it.setObject(1, tenant)
                it.setString(2, reg.doc)
                it.setObject(3, java.sql.Date.valueOf(LocalDate.of(2026, 3, 31)))
                it.setString(4, reg.kind)
                it.setString(5, reg.number)
                it.setObject(6, reg.txDate?.let(java.sql.Date::valueOf))
                it.setString(7, reg.preceding)
                it.executeUpdate()
            }
    }

    private fun version(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
        txDate: LocalDate? = date,
    ) = SubmissionDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref, " +
                    "submission_type, transaction_date) values (?, ?, ?, ?, 'E-1', 'TYPE_1B', ?)",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, doc)
                it.setInt(3, version)
                it.setString(4, state)
                it.setObject(5, txDate?.let(java.sql.Date::valueOf))
                it.executeUpdate()
            }
    }

    private fun seed() {
        entry(t1, Reg(d0, "INVOICE", "INV-099", LocalDate.of(2026, 1, 15)))
        entry(t1, Reg(d1, "CREDIT_NOTE", "INV-100", date, d0))
        version(t1, d1, 1, "SUPERSEDED")
        version(t1, d1, 2, "QUEUED")
        entry(t1, Reg(d2, "INVOICE", "INV-200", date))
        version(t1, d2, 1, "READY")
        entry(t1, Reg(d3, "CREDIT_NOTE", "CN-300", date, "missing-$suffix"))
        version(t1, d3, 1, "READY")
        version(t1, d4, 1, "READY")
        entry(t1, Reg(d5, "INVOICE", "INV-500", date))
        version(t1, d5, 1, "READY", txDate = null)
        entry(t1, Reg(d6, "INVOICE", null, date))
        version(t1, d6, 1, "READY")
        // Tenant T2 holds the same Doc UUID D1 under another version number (submission keys Doc UUID and version;
        // the register keys the Doc UUID alone, so T2 has no entry for D1).
        version(t2, d1, 3, "CANCELLED")
        // A T2 credit note whose preceding entry exists only under T1.
        entry(t2, Reg(d7, "CREDIT_NOTE", "T2-7", date, d0))
        version(t2, d7, 1, "READY")
    }

    private fun read(
        tenant: UUID,
        doc: String,
        version: Int,
    ): ArrivingDocument? {
        SignedBindings.bind(connection, tenant)
        return try {
            reader.of(TenantId(tenant), doc, version).also { connection.commit() }
        } finally {
            connection.rollback()
        }
    }

    @Test
    fun `INV-AC-28666 a credit note returns its facts the preceding facts and the version states in order`() {
        seed()
        read(t1, d1, 1) shouldBe
            ArrivingDocument(
                "E-1",
                "INV-100",
                date,
                true,
                "INV-099",
                LocalDate.of(2026, 1, 15),
                listOf(LifecycleState.SUPERSEDED, LifecycleState.QUEUED),
            )
    }

    @Test
    fun `INV-AC-28666 an invoice gives creditNote false and null preceding values`() {
        seed()
        read(t1, d2, 1) shouldBe
            ArrivingDocument("E-1", "INV-200", date, false, null, null, listOf(LifecycleState.READY))
    }

    @Test
    fun `INV-AC-28666 a credit note whose preceding entry is missing gives both preceding values null`() {
        seed()
        read(t1, d3, 1) shouldBe
            ArrivingDocument("E-1", "CN-300", date, true, null, null, listOf(LifecycleState.READY))
    }

    @Test
    fun `INV-AC-28666 a null document number stays null`() {
        seed()
        read(t1, d6, 1) shouldBe
            ArrivingDocument("E-1", null, date, false, null, null, listOf(LifecycleState.READY))
    }

    @Test
    fun `INV-AC-28666 no submission row gives null`() {
        seed()
        read(t1, "unknown-$suffix", 1) shouldBe null
    }

    @Test
    fun `INV-AC-28666 a submission row without a transaction date gives null`() {
        seed()
        read(t1, d5, 1) shouldBe null
    }

    @Test
    fun `INV-AC-28666 no register entry gives null`() {
        seed()
        read(t1, d4, 1) shouldBe null
    }

    @Test
    fun `INV-AC-28666 the same Doc UUID and version under another tenant gives null`() {
        seed()
        read(t2, d1, 1) shouldBe null
        SignedBindings.bind(connection, t1)
        try {
            reader.of(TenantId(t2), d2, 1) shouldBe null
        } finally {
            connection.rollback()
        }
        read(t2, d2, 1) shouldBe null
    }

    @Test
    fun `INV-AC-28666 a version row of the same Doc UUID under another tenant gives null`() {
        seed()
        read(t2, d1, 3) shouldBe null
    }

    @Test
    fun `INV-AC-28666 the same Doc UUID with another version number gives null`() {
        seed()
        read(t1, d1, 9) shouldBe null
    }

    @Test
    fun `INV-AC-28666 a preceding Doc UUID that exists only under another tenant gives null preceding values`() {
        seed()
        read(t2, d7, 1) shouldBe
            ArrivingDocument("E-1", "T2-7", date, true, null, null, listOf(LifecycleState.READY))
        // Without row level security only the explicit tenant predicate hides the T1 entry of D0.
        SubmissionDb.superuser().use { c ->
            JooqArrivingDocumentFacts(DSL.using(c, SQLDialect.POSTGRES)).of(TenantId(t2), d7, 1) shouldBe
                ArrivingDocument("E-1", "T2-7", date, true, null, null, listOf(LifecycleState.READY))
        }
    }

    @Test
    fun `INV-AC-28666 the queries carry their own tenant predicate without row level security`() {
        seed()
        SubmissionDb.superuser().use { c ->
            val admin = JooqArrivingDocumentFacts(DSL.using(c, SQLDialect.POSTGRES))
            admin.of(TenantId(t2), d2, 1) shouldBe null
            admin.of(TenantId(t2), d7, 1)?.precedingDocumentNumber shouldBe null
            admin.of(TenantId(t2), d1, 3) shouldBe null
            // The T2 version 3 of D1 is not one of the T1 version states.
            admin.of(TenantId(t1), d1, 1)?.versions shouldBe
                listOf(LifecycleState.SUPERSEDED, LifecycleState.QUEUED)
        }
    }

    /** Answers each of the three reads apart: the register entry, the arriving version row and the version states. */
    private fun mocked(
        kind: String,
        state: String,
    ) = DSL.using(
        MockConnection { ctx ->
            val rows =
                when {
                    ctx.sql().contains("obligation_register_entry") -> registerResult(kind)
                    ctx.sql().contains("entity_ref") -> versionResult()
                    else -> statesResult(state)
                }
            arrayOf(MockResult(rows.size, rows))
        },
        SQLDialect.POSTGRES,
    )

    private fun registerResult(kind: String): Result<*> {
        val n = DSL.field("document_number", String::class.java)
        val k = DSL.field("kind", String::class.java)
        val p = DSL.field("preceding_doc_uuid", String::class.java)
        val d = DSL.field("transaction_date", java.sql.Date::class.java)
        val create = DSL.using(SQLDialect.POSTGRES)
        return create
            .newResult(
                n,
                k,
                p,
                d,
            ).also { it.add(create.newRecord(n, k, p, d).values("N-1", kind, null, null)) }
    }

    private fun versionResult(): Result<*> {
        val e = DSL.field("entity_ref", String::class.java)
        val d = DSL.field("transaction_date", java.sql.Date::class.java)
        val create = DSL.using(SQLDialect.POSTGRES)
        return create.newResult(e, d).also {
            it.add(create.newRecord(e, d).values("E-1", java.sql.Date.valueOf(date)))
        }
    }

    private fun statesResult(state: String): Result<*> {
        val s = DSL.field("state", String::class.java)
        val create = DSL.using(SQLDialect.POSTGRES)
        return create.newResult(s).also { it.add(create.newRecord(s).values(state)) }
    }

    @Test
    fun `INV-AC-28666 a kind that is no member name throws`() {
        shouldThrow<IllegalStateException> {
            JooqArrivingDocumentFacts(mocked("BOGUS", "READY")).of(TenantId(t1), d2, 1)
        }.message shouldBe "Unmapped kind"
    }

    @Test
    fun `INV-AC-28666 a version state that is no member name throws`() {
        // Control: the same mock with a member name is read.
        JooqArrivingDocumentFacts(mocked("INVOICE", "READY")).of(TenantId(t1), d2, 1) shouldBe
            ArrivingDocument("E-1", "N-1", date, false, null, null, listOf(LifecycleState.READY))
        shouldThrow<IllegalStateException> {
            JooqArrivingDocumentFacts(mocked("INVOICE", "BOGUS")).of(TenantId(t1), d2, 1)
        }.message shouldBe "Unmapped lifecycle state"
    }
}
