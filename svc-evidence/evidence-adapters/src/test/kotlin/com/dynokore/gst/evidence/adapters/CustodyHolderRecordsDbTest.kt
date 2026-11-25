package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqCustodyHolderRecords
import com.dynokore.gst.evidence.application.retention.CustodyHolder
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** INV-AC-26713 at the database: `offboarding_custody_holder` and its jOOQ store, over the real PostgreSQL. */
class CustodyHolderRecordsDbTest {
    private val at = Instant.parse("2026-11-15T08:30:00Z")
    private val holder = CustodyHolder("A Holder", "a@example.test", "+6590000000")

    private fun record(
        tenant: UUID,
        holder: CustodyHolder = this.holder,
        window: Int? = 45,
    ) = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
        c.autoCommit = false
        SignedBindings.bind(c, tenant)
        JooqCustodyHolderRecords(
            DSL.using(c, SQLDialect.POSTGRES),
        ).record(TenantId(tenant), "OFF-1", holder, window, at)
        c.commit()
    }

    private fun rows(tenant: UUID): List<String> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select custody_holder_name || '|' || custody_holder_email || '|' || " +
                        "coalesce(custody_holder_mobile, '') || '|' || " +
                        "coalesce(notice_window_days::text, '') || '|' || " +
                        "received_at::text from evidence.offboarding_custody_holder " +
                        "where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    private fun visibleTo(
        bound: UUID,
        other: UUID,
    ): Int =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, bound)
            c.prepareStatement("select count(*) from evidence.offboarding_custody_holder where tenant_id = ?").use {
                it.setObject(1, other)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-26713 the row holds the holder and the window equal to the event`() {
        val tenant = UUID.randomUUID()
        record(tenant)
        rows(tenant).single().startsWith("A Holder|a@example.test|+6590000000|45|2026-11-15 08:30:00") shouldBe true
    }

    @Test
    fun `INV-AC-26713 a second delivery leaves one row and raises nothing`() {
        val tenant = UUID.randomUUID()
        record(tenant)
        record(tenant, CustodyHolder("Other", "o@example.test", null), 7)
        rows(tenant).size shouldBe 1
        rows(tenant).single().startsWith("A Holder|") shouldBe true
    }

    @Test
    fun `INV-AC-26713 optional mobile and window are stored as null`() {
        val tenant = UUID.randomUUID()
        record(tenant, CustodyHolder("A", "a@example.test", null), null)
        rows(tenant).single().startsWith("A|a@example.test|||2026") shouldBe true
    }

    @Test
    fun `INV-AC-26713 a window below 1 is refused by the table`() {
        val tenant = UUID.randomUUID()
        shouldThrow<Exception> { record(tenant, window = 0) }
        rows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26713 another tenant reads no row`() {
        val tenant = UUID.randomUUID()
        record(tenant)
        visibleTo(tenant, tenant) shouldBe 1
        visibleTo(UUID.randomUUID(), tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-26713 a tenant cannot write a row for another tenant`() {
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        shouldThrow<DataAccessException> {
            EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, other)
                JooqCustodyHolderRecords(DSL.using(c, SQLDialect.POSTGRES))
                    .record(TenantId(tenant), "OFF-1", holder, 1, at)
            }
        }
        rows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26713 the three holder columns are class personal`() {
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select count(*) from pg_description d join pg_class k on k.oid = d.objoid " +
                        "join pg_namespace n on n.oid = k.relnamespace " +
                        "where n.nspname = 'evidence' and k.relname = 'offboarding_custody_holder' " +
                        "and d.objsubid > 0 and d.description = 'class: personal'",
                ).use {
                    it.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 3
                    }
                }
        }
    }
}
