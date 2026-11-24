package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqImportedPriorEvidenceStore
import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The imported prior evidence store against the test database as `submission_runtime` (INV-AC-28453). */
class ImportedPriorEvidenceStoreDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                }
            }
        }

    private val store = JooqImportedPriorEvidenceStore(dsl, runner)
    private val date = LocalDate.of(2026, 2, 27)

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return block()
        } finally {
            bound.remove()
        }
    }

    private fun rec(
        entity: String = "E-1",
        ack: String = "ACK-1",
        on: LocalDate = date,
    ) = ImportedPriorRecord(entity, "INV-100", on, ack)

    private fun put(
        t: UUID,
        r: ImportedPriorRecord,
    ) = asTenant(t) { store.record(TenantId(t), r, "P1") }

    private fun list(
        t: UUID,
        entity: String = "E-1",
    ) = asTenant(t) { store.listFor(TenantId(t), entity) }

    private fun ownerStrings(sql: String): List<String> =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    @Test
    fun `INV-AC-28453 one record per document, the repeat stores none and another acknowledgement stores nothing`() {
        val t1 = UUID.randomUUID()
        put(t1, rec()) shouldBe true
        put(t1, rec()) shouldBe false
        shouldThrow<ContractViolation> { put(t1, rec(ack = "ACK-2")) }
        list(t1) shouldBe listOf(rec())
    }

    @Test
    fun `INV-AC-28453 the stored prior_solution equals the argument and a repeat leaves it unchanged`() {
        val t1 = UUID.randomUUID()
        val stored = {
            ownerStrings("select prior_solution from submission.imported_prior_evidence where tenant_id = '$t1'")
        }
        asTenant(t1) { store.record(TenantId(t1), rec(), "Legacy Books") } shouldBe true
        stored() shouldBe listOf("Legacy Books")
        asTenant(t1) { store.record(TenantId(t1), rec(), "Other Books") } shouldBe false
        stored() shouldBe listOf("Legacy Books")
    }

    @Test
    fun `INV-AC-28453 the same document number of another entity or another date is its own record`() {
        val t1 = UUID.randomUUID()
        put(t1, rec()) shouldBe true
        put(t1, rec(entity = "E-2")) shouldBe true
        put(t1, rec(on = date.plusDays(1), ack = "ACK-3")) shouldBe true
        list(t1, "E-2") shouldBe listOf(rec(entity = "E-2"))
        list(t1) shouldBe listOf(rec(), rec(on = date.plusDays(1), ack = "ACK-3"))
    }

    @Test
    fun `INV-AC-28453 a record of another tenant is never read, even when asked for by id`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        put(t1, rec()) shouldBe true
        list(t2) shouldBe emptyList()
        asTenant(t1) { store.listFor(TenantId(t2), "E-1") } shouldBe emptyList()
        put(t2, rec(ack = "ACK-9")) shouldBe true
        list(t1) shouldBe listOf(rec())
        list(t2) shouldBe listOf(rec(ack = "ACK-9"))
    }

    @Test
    fun `INV-AC-28453 the runtime role holds select and insert and nothing else`() {
        val granted =
            ownerStrings(
                "select privilege_type from information_schema.role_table_grants " +
                    "where grantee = 'submission_runtime' and table_schema = 'submission' " +
                    "and table_name = 'imported_prior_evidence' order by 1",
            )
        granted shouldBe listOf("INSERT", "SELECT")
    }
}
