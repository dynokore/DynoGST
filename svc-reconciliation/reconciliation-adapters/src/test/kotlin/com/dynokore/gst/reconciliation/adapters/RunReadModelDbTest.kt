package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReadModel
import com.dynokore.gst.reconciliation.application.run.DocumentLineFacts
import com.dynokore.gst.reconciliation.application.run.DocumentVersionFacts
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.sgd.AllocationLine
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocator
import com.dynokore.gst.reconciliation.domain.sgd.StatedSgd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

private fun line(
    n: Int,
    amount: String,
    tax: String,
) = DocumentLineFacts(n, "SR", BigDecimal(amount), BigDecimal(tax))

/**
 * INV-AC-24341 at the database (INV-T-24344): the run read model over a real PostgreSQL schema with the runtime login
 * and the tenant bound; no fakes.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RunReadModelDbTest {
    private val d1 = UUID.randomUUID().toString()
    private val d2 = UUID.randomUUID().toString()
    private val d3 = UUID.randomUUID().toString()
    private val d4 = UUID.randomUUID().toString()
    private val d5 = UUID.randomUUID().toString()
    private val dT2 = UUID.randomUUID().toString()

    private fun <T> inTenant(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                block(connection).also { connection.commit() }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun <T> model(
        tenant: UUID,
        block: (JooqRunReadModel) -> T,
    ): T = inTenant(tenant) { block(JooqRunReadModel(DSL.using(it, SQLDialect.POSTGRES))) }

    private class Money(
        val currency: String = "SGD",
        val rate: String? = null,
        val excl: String? = null,
        val tax: String? = null,
        val lines: List<DocumentLineFacts> = listOf(line(1, "100.00", "9.00"), line(2, "50.00", "4.50")),
    )

    private fun facts(
        doc: String,
        version: Int = 1,
        sequence: Long = 10,
        period: String = "2026-Q1",
        money: Money = Money(),
    ) = DocumentVersionFacts(
        doc,
        version,
        sequence,
        period,
        LocalDate.parse("2026-02-10"),
        "IN_SCOPE",
        null,
        Direction.SALE,
        money.currency,
        money.rate?.let(::BigDecimal),
        money.excl?.let(::BigDecimal),
        money.tax?.let(::BigDecimal),
        money.lines,
    )

    private fun purchase(
        doc: String,
        money: Money = Money(),
    ) = facts(doc, money = money).copy(direction = Direction.PURCHASE)

    private fun d2Facts() =
        facts(
            d2,
            money =
                Money(
                    currency = "USD",
                    rate = "1.35",
                    excl = "182.25",
                    tax = "16.40",
                    lines = listOf(line(1, "100.00", "9.00"), line(2, "35.00", "3.15")),
                ),
        )

    private fun sqlState(block: () -> Unit): String? {
        val failure = shouldThrow<Exception> { block() }
        val chain = generateSequence<Throwable>(failure) { it.cause }
        return chain.filterIsInstance<SQLException>().firstOrNull()?.sqlState
    }

    private fun scalar(sql: String): Any? =
        ReconciliationDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getObject(1)
                }
            }
        }

    private fun execute(
        tenant: UUID,
        sql: String,
    ) = inTenant(tenant) { c -> c.createStatement().use { it.executeUpdate(sql) } }

    private fun superuserExecute(sql: String) =
        ReconciliationDb.superuser().use { c ->
            c.createStatement().use {
                it.executeUpdate(sql)
            }
        }

    private fun docsOf(
        tenant: UUID,
        entity: String,
        period: String,
    ) = model(tenant) { it.load(tenant.toString(), entity, period) }.map { it.docUuid }

    @Test
    fun `INV-AC-24341 recordVersion is true once and false on the repeat which leaves the stored row unchanged`() {
        val t1 = UUID.randomUUID()
        model(t1) { it.recordVersion(t1.toString(), "E1", facts(d1)) } shouldBe true
        val changed = facts(d1, money = Money(currency = "USD", lines = listOf(line(1, "1.00", "0.00"))))
        model(t1) { it.recordVersion(t1.toString(), "E1", changed) } shouldBe false
        scalar("select currency from reconciliation.document_version where tenant_id = '$t1'") shouldBe "SGD"
        scalar("select count(*) from reconciliation.document_line where tenant_id = '$t1'") shouldBe 2L
    }

    @Test
    fun `INV-AC-24341 reassignPeriod with a higher docsequence moves the document and a lower one changes nothing`() {
        val t1 = UUID.randomUUID()
        model(t1) { it.recordVersion(t1.toString(), "E1", facts(d1, sequence = 10)) } shouldBe true
        model(t1) { it.reassignPeriod(t1.toString(), "E1", d1, 20, "2026-Q2") } shouldBe true
        model(t1) { it.reassignPeriod(t1.toString(), "E1", d1, 15, "2026-Q3") } shouldBe false
        docsOf(t1, "E1", "2026-Q1") shouldBe emptyList()
        docsOf(t1, "E1", "2026-Q3") shouldBe emptyList()
        docsOf(t1, "E1", "2026-Q2") shouldBe listOf(d1)
        scalar("select docsequence from reconciliation.document_period where tenant_id = '$t1'") shouldBe 20L
    }

    @Test
    fun `INV-AC-24341 a reassignment that arrives first is not overwritten by a later lower docsequence version`() {
        val t1 = UUID.randomUUID()
        model(t1) { it.reassignPeriod(t1.toString(), "E1", d1, 20, "2026-Q2") } shouldBe true
        model(t1) { it.recordVersion(t1.toString(), "E1", facts(d1, sequence = 10)) } shouldBe true
        docsOf(t1, "E1", "2026-Q1") shouldBe emptyList()
        docsOf(t1, "E1", "2026-Q2") shouldBe listOf(d1)
    }

    @Test
    fun `INV-AC-24341 the load holds the document of its entity, period and tenant with the allocation applied`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        model(t1) {
            it.recordVersion(t1.toString(), "E1", facts(d1))
            it.recordVersion(t1.toString(), "E1", d2Facts())
            it.recordVersion(t1.toString(), "E2", facts(d3))
        }
        model(t2) { it.recordVersion(t2.toString(), "E1", facts(dT2)) }
        model(t1) { it.reassignPeriod(t1.toString(), "E1", d1, 20, "2026-Q2") }
        val loaded = model(t1) { it.load(t1.toString(), "E1", "2026-Q1") }
        loaded.map { it.docUuid } shouldBe listOf(d2)
        val allocated =
            loaded
                .single()
                .versions
                .single()
                .allocation
        allocated shouldBe
            SgdAllocator.allocate(
                "USD",
                listOf(
                    AllocationLine(1, BigDecimal("100.00"), BigDecimal("9.00"), "SR"),
                    AllocationLine(2, BigDecimal("35.00"), BigDecimal("3.15"), "SR"),
                ),
                StatedSgd(BigDecimal("182.25"), BigDecimal("16.40"), BigDecimal("1.35")),
            )
        val lines = (allocated as SgdAllocation.Allocated).lines
        lines.fold(BigDecimal.ZERO) { a, l -> a + l.taxableAmountSgd } shouldBe BigDecimal("182.25")
        lines.fold(BigDecimal.ZERO) { a, l -> a + l.taxAmountSgd } shouldBe BigDecimal("16.40")
        docsOf(t1, "E2", "2026-Q1") shouldBe listOf(d3)
        docsOf(t2, "E1", "2026-Q1") shouldBe listOf(dT2)
    }

    @Test
    fun `INV-AC-24341 the load carries its own tenant predicate when row-level security is bypassed`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        model(t1) { it.recordVersion(t1.toString(), "E1", facts(d1)) }
        model(t2) { it.recordVersion(t2.toString(), "E1", facts(dT2)) }
        ReconciliationDb.superuser().use { c ->
            val store = JooqRunReadModel(DSL.using(c, SQLDialect.POSTGRES))
            store.load(t1.toString(), "E1", "2026-Q1").map { it.docUuid } shouldBe listOf(d1)
            store.load(t2.toString(), "E1", "2026-Q1").map { it.docUuid } shouldBe listOf(dT2)
        }
    }

    @Test
    fun `INV-AC-24341 an acknowledgement recorded before version 2 marks version 1 acknowledged only`() {
        val t1 = UUID.randomUUID()
        val at = Instant.parse("2026-03-01T00:00:00Z")
        model(t1) { it.recordAcknowledgement(t1.toString(), d1, 1, "ACK-1", at) } shouldBe true
        model(t1) { it.recordAcknowledgement(t1.toString(), d1, 1, "ACK-1", at) } shouldBe false
        model(t1) {
            it.recordVersion(t1.toString(), "E1", facts(d1, version = 1, sequence = 10))
            it.recordVersion(t1.toString(), "E1", facts(d1, version = 2, sequence = 11))
        }
        val versions = model(t1) { it.load(t1.toString(), "E1", "2026-Q1") }.single().versions
        versions.map { it.version to it.acknowledged } shouldBe listOf(1 to true, 2 to false)
    }

    @Test
    fun `INV-AC-24341 a missing SGD total is NotReconcilable and a document without lines is kept`() {
        val t1 = UUID.randomUUID()
        model(t1) {
            it.recordVersion(t1.toString(), "E1", facts(d4, money = Money(currency = "USD", rate = "1.35")))
            it.recordVersion(t1.toString(), "E1", facts(d5, money = Money(lines = emptyList())))
        }
        val loaded = model(t1) { it.load(t1.toString(), "E1", "2026-Q1") }.associateBy { it.docUuid }
        loaded.keys shouldBe setOf(d4, d5)
        val missing = SgdAllocation.NotReconcilable(NotReconcilableReason.MISSING_SGD_TOTAL)
        loaded
            .getValue(d4)
            .versions
            .single()
            .allocation shouldBe missing
        loaded
            .getValue(d5)
            .versions
            .single()
            .allocation shouldBe SgdAllocation.Allocated(emptyList())
    }

    @Test
    fun `INV-AC-24341 the UPDATE and the DELETE of a version row fail with SQLSTATE 42501`() {
        val t1 = UUID.randomUUID()
        model(t1) { it.recordVersion(t1.toString(), "E1", facts(d1)) }
        sqlState { execute(t1, "update reconciliation.document_version set currency = 'USD'") } shouldBe "42501"
        sqlState { execute(t1, "delete from reconciliation.document_version") } shouldBe "42501"
        scalar("select currency from reconciliation.document_version where tenant_id = '$t1'") shouldBe "SGD"
    }

    @Test
    fun `INV-AC-24341 the append-only trigger refuses UPDATE and DELETE on all three tables even for the superuser`() {
        val t1 = UUID.randomUUID()
        model(t1) {
            it.recordVersion(t1.toString(), "E1", facts(d1))
            it.recordAcknowledgement(t1.toString(), d1, 1, "ACK-1", Instant.parse("2026-03-01T00:00:00Z"))
        }
        val assignments =
            mapOf(
                "document_version" to "currency = 'USD'",
                "document_line" to "tax_code = 'ZR'",
                "document_acknowledgement" to "acknowledgement_id = 'ACK-2'",
            )
        assignments.forEach { (table, assignment) ->
            val before = scalar("select count(*) from reconciliation.$table where tenant_id = '$t1'")
            listOf("update reconciliation.$table set $assignment", "delete from reconciliation.$table").forEach { sql ->
                val failure = shouldThrow<SQLException> { superuserExecute(sql) }
                failure.sqlState shouldBe "42501"
                failure.message!!.contains("append-only") shouldBe true
            }
            scalar("select count(*) from reconciliation.$table where tenant_id = '$t1'") shouldBe before
        }
        scalar("select currency from reconciliation.document_version where tenant_id = '$t1'") shouldBe "SGD"
        scalar("select min(tax_code) from reconciliation.document_line where tenant_id = '$t1'") shouldBe "SR"
    }

    @Test
    fun `INV-AC-24341 the acknowledgement has no foreign key and every table is forced and guarded`() {
        val ack = "'reconciliation.document_acknowledgement'::regclass"
        scalar("select count(*) from pg_constraint where contype = 'f' and conrelid = $ack") shouldBe 0L
        val tables = "('document_version','document_line','document_period','document_acknowledgement')"
        scalar("select count(*) from pg_class where relname in $tables and relforcerowsecurity") shouldBe 4L
        val guarded = "tgrelid::regclass::text like 'reconciliation.document_%' and not tgisinternal"
        scalar("select count(*) from pg_trigger where $guarded") shouldBe 3L
    }

    private fun versionRows(
        t: UUID,
        doc: String,
    ) = scalar("select count(*) from reconciliation.document_version where tenant_id = '$t' and doc_uuid = '$doc'")

    private fun lineRows(
        t: UUID,
        doc: String,
        entity: String,
        direction: String,
    ) = scalar(
        "select count(*) from reconciliation.document_line where tenant_id = '$t' and doc_uuid = '$doc' " +
            "and entity_ref = '$entity' and direction = '$direction'",
    )

    private fun periodColumn(
        t: UUID,
        entity: String,
        column: String,
    ) = scalar("select $column from reconciliation.document_period where tenant_id = '$t' and entity_ref = '$entity'")

    private fun recordPair(t: UUID) {
        model(t) {
            it.recordVersion(t.toString(), "E-001", facts(d1)) shouldBe true
            it.recordVersion(
                t.toString(),
                "E-002",
                purchase(d1, Money(lines = listOf(line(1, "7.00", "0.63")))),
            ) shouldBe true
        }
    }

    @Test
    fun `INV-AC-26646 a SALE and a PURCHASE version of one Doc UUID for two entities are two rows`() {
        val t = UUID.randomUUID()
        recordPair(t)
        versionRows(t, d1) shouldBe 2L
        lineRows(t, d1, "E-001", "SALE") shouldBe 2L
        lineRows(t, d1, "E-002", "PURCHASE") shouldBe 1L
    }

    @Test
    fun `INV-AC-26646 the load of each entity holds only its own side`() {
        val t = UUID.randomUUID()
        recordPair(t)
        val e1 = model(t) { it.load(t.toString(), "E-001", "2026-Q1") }.single()
        val e2 = model(t) { it.load(t.toString(), "E-002", "2026-Q1") }.single()
        e1.docUuid shouldBe d1
        e2.docUuid shouldBe d1
        e1.versions.map { it.direction } shouldBe listOf(Direction.SALE)
        e2.versions.map { it.direction } shouldBe listOf(Direction.PURCHASE)
        e1.versions.single().allocation shouldBe
            SgdAllocator.allocate(
                "SGD",
                listOf(
                    AllocationLine(1, BigDecimal("100.00"), BigDecimal("9.00"), "SR"),
                    AllocationLine(2, BigDecimal("50.00"), BigDecimal("4.50"), "SR"),
                ),
                null,
            )
        e2.versions.single().allocation shouldBe
            SgdAllocator.allocate("SGD", listOf(AllocationLine(1, BigDecimal("7.00"), BigDecimal("0.63"), "SR")), null)
    }

    @Test
    fun `INV-AC-26646 a repeat of either record returns false and stores no third row`() {
        val t = UUID.randomUUID()
        recordPair(t)
        model(t) { it.recordVersion(t.toString(), "E-001", facts(d1)) } shouldBe false
        model(t) {
            it.recordVersion(t.toString(), "E-002", purchase(d1))
        } shouldBe false
        versionRows(t, d1) shouldBe 2L
        lineRows(t, d1, "E-001", "SALE") shouldBe 2L
        lineRows(t, d1, "E-002", "PURCHASE") shouldBe 1L
    }

    @Test
    fun `INV-AC-26646 the same entity with the opposite direction is a second row and the same key stays one`() {
        val t = UUID.randomUUID()
        model(t) { it.recordVersion(t.toString(), "E-001", facts(d1)) } shouldBe true
        model(t) { it.recordVersion(t.toString(), "E-001", facts(d1)) } shouldBe false
        versionRows(t, d1) shouldBe 1L
        model(t) { it.recordVersion(t.toString(), "E-001", purchase(d1)) } shouldBe true
        versionRows(t, d1) shouldBe 2L
    }

    @Test
    fun `INV-AC-26646 reassignPeriod for one entity leaves the period row of the other entity unchanged`() {
        val t = UUID.randomUUID()
        recordPair(t)
        model(t) { it.reassignPeriod(t.toString(), "E-001", d1, 20, "2026-Q2") } shouldBe true
        periodColumn(t, "E-001", "period_ref") shouldBe "2026-Q2"
        periodColumn(t, "E-002", "period_ref") shouldBe "2026-Q1"
        periodColumn(t, "E-002", "docsequence") shouldBe 10L
    }

    @Test
    fun `INV-AC-26646 the primary keys are widened and the guards equal the previous ones`() {
        fun pk(table: String) =
            scalar(
                "select string_agg(a.attname, ',' order by k.ord) from pg_index i " +
                    "cross join lateral unnest(i.indkey) with ordinality k(attnum, ord) " +
                    "join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum " +
                    "where i.indisprimary and i.indrelid = 'reconciliation.$table'::regclass",
            )
        pk("document_version") shouldBe "tenant_id,doc_uuid,entity_ref,direction,version"
        pk("document_line") shouldBe "tenant_id,doc_uuid,entity_ref,direction,version,line_number"
        pk("document_period") shouldBe "tenant_id,doc_uuid,entity_ref"
        pk("document_acknowledgement") shouldBe "tenant_id,doc_uuid,version"
        scalar(
            "select count(*) from pg_description d " +
                "join pg_attribute a on a.attrelid = d.objoid and a.attnum = d.objsubid " +
                "where d.objoid = 'reconciliation.document_line'::regclass " +
                "and a.attname in ('entity_ref','direction') " +
                "and d.description = 'class: operational'",
        ) shouldBe 2L
        scalar(
            "select count(*) from pg_policy where polrelid in " +
                "('reconciliation.document_line'::regclass, 'reconciliation.document_version'::regclass)",
        ) shouldBe 2L
        scalar(
            "select count(*) from pg_trigger where tgrelid = 'reconciliation.document_line'::regclass " +
                "and tgname = 'document_line_append_only' and not tgisinternal",
        ) shouldBe 1L
        scalar(
            "select count(*) from information_schema.role_table_grants where table_schema = 'reconciliation' " +
                "and table_name = 'document_line' and grantee = 'reconciliation_runtime' " +
                "and privilege_type in ('UPDATE','DELETE','TRUNCATE')",
        ) shouldBe 0L
        scalar(
            "select count(*) from information_schema.role_table_grants where table_schema = 'reconciliation' " +
                "and table_name = 'document_line' and grantee = 'reconciliation_runtime' " +
                "and privilege_type in ('SELECT','INSERT')",
        ) shouldBe 2L
    }
}
