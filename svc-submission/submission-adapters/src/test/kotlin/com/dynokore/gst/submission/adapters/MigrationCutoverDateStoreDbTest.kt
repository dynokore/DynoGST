package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqMigrationCutoverDateStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The migration cutover date store against the test database as `submission_runtime` (INV-AC-28451). */
class MigrationCutoverDateStoreDbTest {
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

    private val store = JooqMigrationCutoverDateStore(dsl, runner)

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

    private fun record(
        tenant: UUID,
        date: LocalDate,
        effective: LocalDate,
    ) = asTenant(tenant) { store.record(TenantId(tenant), "E", date, effective) }

    private fun find(tenant: UUID) = asTenant(tenant) { store.find(TenantId(tenant), "E") }

    @Test
    fun `INV-AC-28451 the stored cutover date follows the highest effective date and equal or older ones change nothing`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        record(t1, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1)) shouldBe true
        find(t1) shouldBe LocalDate.of(2026, 4, 1)
        record(t1, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 1)) shouldBe true
        find(t1) shouldBe LocalDate.of(2026, 7, 1)
        record(t1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1)) shouldBe false
        find(t1) shouldBe LocalDate.of(2026, 7, 1)
        record(t1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)) shouldBe false
        find(t1) shouldBe LocalDate.of(2026, 7, 1)
        find(t2) shouldBe null
    }

    @Test
    fun `INV-AC-28451 a row of another tenant or entity is never read nor changed`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        record(t1, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 1)) shouldBe true
        find(t2) shouldBe null
        // Row level security alone would hide a missing predicate: T2 is asked for while T1 stays bound.
        asTenant(t1) { store.find(TenantId(t2), "E") } shouldBe null
        record(t2, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 1, 1)) shouldBe true
        find(t1) shouldBe LocalDate.of(2026, 7, 1)
        find(t2) shouldBe LocalDate.of(2026, 9, 1)
        asTenant(t1) { store.find(TenantId(t1), "E-2") } shouldBe null
    }
}
