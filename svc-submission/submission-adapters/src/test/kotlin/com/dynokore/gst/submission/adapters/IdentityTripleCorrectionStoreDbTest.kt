package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqIdentityTripleCorrectionStore
import com.dynokore.gst.submission.application.identitytriple.IdentityTripleCorrectedData
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** The identity triple correction store against the test database as `submission_runtime` (INV-AC-26836). */
class IdentityTripleCorrectionStoreDbTest {
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

    private val store = JooqIdentityTripleCorrectionStore(dsl, runner)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()

    private companion object {
        const val TABLE = "submission.identity_triple_correction"
        const val SELECT_ROWS = "select effective_from, access_point_escalation from $TABLE"
    }

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

    private fun data(
        change: String = "R1",
        from: LocalDate = LocalDate.of(2026, 3, 1),
    ) = IdentityTripleCorrectedData("E1", "C1", from, change, true)

    private fun record(
        tenant: UUID,
        d: IdentityTripleCorrectedData,
    ) = asTenant(tenant) { store.record(TenantId(tenant), d) }

    private fun find(
        tenant: UUID,
        change: String = "R1",
    ) = asTenant(tenant) { store.find(TenantId(tenant), "E1", "C1", change) }

    /** Rows of the table visible to [tenant] as (effective_from, access_point_escalation). */
    private fun rows(tenant: UUID): List<Pair<LocalDate, Boolean>> =
        asTenant(tenant) {
            SignedBindings.bind(connection, tenant)
            val result = mutableListOf<Pair<LocalDate, Boolean>>()
            connection.createStatement().use { st ->
                st.executeQuery(SELECT_ROWS).use {
                    while (it.next()) result += it.getDate(1).toLocalDate() to it.getBoolean(2)
                }
            }
            connection.commit()
            result
        }

    /** Runs a statement as [tenant]; the transaction is rolled back after an error. */
    private fun exec(
        tenant: UUID,
        sql: String,
    ) = asTenant(tenant) {
        SignedBindings.bind(connection, tenant)
        try {
            connection.createStatement().use { it.executeUpdate(sql) }
        } finally {
            connection.rollback()
        }
    }

    private fun insertSql(
        change: String = "'R9'",
        escalation: String = "true",
        tenant: String? = "'$t1'",
    ) = "insert into submission.identity_triple_correction (" +
        (if (tenant != null) "tenant_id, " else "") +
        "entity_ref, constitution, change_ref, effective_from, access_point_escalation) values (" +
        (if (tenant != null) "$tenant, " else "") + "'E9', 'C9', $change, date '2026-03-01', $escalation)"

    @Test
    fun `INV-AC-26836 case 16 a first record returns true and stores one row`() {
        record(t1, data()) shouldBe true
        rows(t1) shouldBe listOf(LocalDate.of(2026, 3, 1) to true)
    }

    @Test
    fun `INV-AC-26836 case 17 the same key again returns false and keeps the first effective_from`() {
        record(t1, data()) shouldBe true
        record(t1, data(from = LocalDate.of(2026, 4, 1))) shouldBe false
        rows(t1) shouldBe listOf(LocalDate.of(2026, 3, 1) to true)
        find(t1)?.effectiveFrom shouldBe LocalDate.of(2026, 3, 1)
    }

    @Test
    fun `INV-AC-26836 case 18 another change reference returns true and the table has two rows`() {
        record(t1, data()) shouldBe true
        record(t1, data(change = "R2")) shouldBe true
        rows(t1).size shouldBe 2
    }

    @Test
    fun `INV-AC-26836 case 19 the same key under another tenant returns true`() {
        record(t1, data()) shouldBe true
        record(t2, data()) shouldBe true
    }

    @Test
    fun `INV-AC-26836 case 20 find and select see only the bound tenant`() {
        record(t1, data()) shouldBe true
        record(t2, data(change = "R2")) shouldBe true
        find(t1) shouldBe data()
        find(t1, "R2") shouldBe null
        find(t2, "R2") shouldBe data(change = "R2")
        find(t2) shouldBe null
        rows(t1).size shouldBe 1
        rows(t2).size shouldBe 1
    }

    @Test
    fun `INV-AC-26836 case 21 a 41-character change_ref, escalation false and a missing tenant_id fail`() {
        exec(t1, insertSql()) shouldBe 1
        shouldThrow<SQLException> { exec(t1, insertSql(change = "'${"x".repeat(41)}'")) }
        shouldThrow<SQLException> { exec(t1, insertSql(escalation = "false")) }
        shouldThrow<SQLException> { exec(t1, insertSql(tenant = null)) }
    }

    @Test
    fun `INV-AC-26836 case 22 submission_runtime cannot update or delete a row`() {
        record(t1, data()) shouldBe true
        shouldThrow<SQLException> { exec(t1, "update $TABLE set effective_from = date '2027-01-01'") }
        shouldThrow<SQLException> { exec(t1, "delete from $TABLE") }
        rows(t1).size shouldBe 1
    }
}
