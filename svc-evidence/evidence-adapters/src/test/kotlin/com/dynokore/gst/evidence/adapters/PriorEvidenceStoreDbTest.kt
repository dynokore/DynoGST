package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqPriorEvidenceStore
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.migration.PriorEvidenceRow
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEndResult
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.domain.retention.PriorEvidencePeriodRef
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * INV-AC-28460: [JooqPriorEvidenceStore] stores imported prior evidence insert-only in the retention unit of the
 * quarter of its transaction date. Real PostgreSQL through the repository fixtures and the runtime login.
 */
class PriorEvidenceStoreDbTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC)
    private val tenants = mutableListOf<UUID>()

    private fun tenant(): UUID = UUID.randomUUID().also { tenants.add(it) }

    /** Detached tables are standalone until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        EvidenceDb.superuser().use { c ->
            tenants.forEach { t ->
                val statements =
                    c
                        .prepareStatement(
                            "select 'drop table evidence.' || m.partition_table " +
                                "from evidence.retention_partition_member m " +
                                "join pg_class t on t.relname = m.partition_table " +
                                "and t.relnamespace = 'evidence'::regnamespace " +
                                "where m.tenant_id = ? and not t.relispartition",
                        ).use { st ->
                            st.setObject(1, t)
                            st.executeQuery().use { rs ->
                                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                            }
                        }
                c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            }
        }
        tenants.clear()
    }

    private fun <T> runtime(
        tenant: UUID,
        block: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private fun scalar(
        sql: String,
        vararg args: Any?,
    ): String? =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun row(
        entity: String = "E1",
        document: String = "DOC-1",
        date: LocalDate = LocalDate.of(2026, 2, 27),
        uuid: UUID? = null,
        by: String = "u-importer",
    ) = PriorEvidenceRow(
        UUID.randomUUID(),
        entity,
        PriorEvidencePeriodRef.of(date),
        document,
        date,
        "ACK-$document",
        "LEGACY",
        uuid,
        clock.instant(),
        by,
    )

    private fun insert(
        tenant: UUID,
        row: PriorEvidenceRow,
    ) = runtime(tenant) { JooqPriorEvidenceStore(it).insert(TenantId(tenant), row) }

    private fun column(
        tenant: UUID,
        document: String,
        expression: String,
    ): String? =
        scalar(
            "select $expression from evidence.prior_evidence where tenant_id = ? and document_number = ?",
            tenant,
            document,
        )

    private fun members(tenant: UUID): String =
        scalar(
            "select count(*) || ':' || min(state) || ':' || min(chain_table) " +
                "from evidence.retention_partition_member where tenant_id = ? and chain_table = 'prior_evidence'",
            tenant,
        ).orEmpty()

    private fun units(tenant: UUID): String =
        scalar("select count(*) from evidence.retention_partition where tenant_id = ?", tenant).orEmpty()

    @Test
    fun `INV-AC-28460 an insert dated 2026-02-27 creates the attached prior_evidence member of the unit 2026-Q1`() {
        val t = tenant()
        insert(t, row())
        members(t) shouldBe "1:ATTACHED:prior_evidence"
        scalar(
            "select period_from from evidence.retention_partition where tenant_id = ? and entity_ref = 'E1'",
            t,
        ) shouldBe "2026-Q1"
        insert(t, row(document = "DOC-2", date = LocalDate.of(2026, 3, 1)))
        members(t) shouldBe "1:ATTACHED:prior_evidence"
        units(t) shouldBe "1"
        scalar("select count(*) from evidence.prior_evidence where tenant_id = ?", t) shouldBe "2"
    }

    @Test
    fun `INV-AC-28460 a record of the next quarter lands in a unit of its own, a look-alike period never shares one`() {
        val t = tenant()
        insert(t, row(date = LocalDate.of(2026, 3, 31)))
        insert(t, row(document = "DOC-2", date = LocalDate.of(2026, 4, 1)))
        units(t) shouldBe "2"
        scalar(
            "select string_agg(period_ref, ',' order by period_ref) from evidence.prior_evidence where tenant_id = ?",
            t,
        ) shouldBe "2026-Q1,2026-Q2"
    }

    @Test
    fun `INV-AC-28460 a record of a second tenant is invisible to the first`() {
        val t1 = tenant()
        val t2 = tenant()
        insert(t2, row())
        val date = LocalDate.of(2026, 2, 27)
        runtime(t1) { JooqPriorEvidenceStore(it).find(TenantId(t1), "E1", "DOC-1", date) } shouldBe null
        // the row exists for its own tenant, and a find as tenant 1 naming tenant 2 sees nothing either
        runtime(t2) { JooqPriorEvidenceStore(it).find(TenantId(t2), "E1", "DOC-1", date) }?.acknowledgementRef shouldBe
            "ACK-DOC-1"
        runtime(t1) { JooqPriorEvidenceStore(it).find(TenantId(t2), "E1", "DOC-1", date) } shouldBe null
    }

    @Test
    fun `INV-AC-28460 find returns the stored acknowledgement and null for three look-alike identities`() {
        val t = tenant()
        insert(t, row())
        val date = LocalDate.of(2026, 2, 27)
        runtime(t) { dsl ->
            val store = JooqPriorEvidenceStore(dsl)
            store.find(TenantId(t), "E1", "DOC-1", date)?.acknowledgementRef shouldBe "ACK-DOC-1"
            store.find(TenantId(t), "E1", "DOC-10", date) shouldBe null
            store.find(TenantId(t), "E1", "DOC-1", date.plusDays(1)) shouldBe null
            store.find(TenantId(t), "E2", "DOC-1", date) shouldBe null
        }
    }

    @Test
    fun `INV-AC-28460 prior_doc_uuid equals the given UUID and is null when none was given, imported_by is the user`() {
        val t = tenant()
        val uuid = UUID.randomUUID()
        insert(t, row(document = "DOC-1", uuid = uuid, by = "u-42"))
        insert(t, row(document = "DOC-2"))
        column(t, "DOC-1", "prior_doc_uuid::text") shouldBe uuid.toString()
        column(t, "DOC-2", "prior_doc_uuid::text") shouldBe null
        column(t, "DOC-1", "imported_by") shouldBe "u-42"
    }

    @Test
    fun `INV-AC-28460 an update and a delete as the runtime login are refused by the database`() {
        val t = tenant()
        insert(t, row())
        listOf(
            "update evidence.prior_evidence set prior_solution = 'X' where tenant_id = '$t'",
            "delete from evidence.prior_evidence where tenant_id = '$t'",
        ).forEach { sql ->
            val state =
                try {
                    runtime(t) { it.execute(sql) }
                    null
                } catch (e: DataAccessException) {
                    e.sqlState()
                }
            state shouldBe "42501"
        }
        scalar("select count(*) from evidence.prior_evidence where tenant_id = ?", t) shouldBe "1"
    }

    private fun connection(tenant: UUID): Connection =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).also {
            it.autoCommit = false
            SignedBindings.bind(it, tenant)
        }

    /** The second transaction: locks, finds and inserts only when nothing was found; commits. */
    private class Importer(
        private val connection: Connection,
        private val tenant: TenantId,
        private val date: LocalDate,
        private val newRow: () -> PriorEvidenceRow,
    ) {
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val sawRow = AtomicBoolean(false)
        val failure = arrayOfNulls<Throwable>(1)

        fun run() {
            try {
                val store = JooqPriorEvidenceStore(DSL.using(connection, SQLDialect.POSTGRES))
                started.countDown()
                store.lock(tenant, "E1", "DOC-1", date)
                val found = store.find(tenant, "E1", "DOC-1", date)
                sawRow.set(found != null)
                if (found == null) store.insert(tenant, newRow())
                connection.commit()
            } catch (e: Exception) {
                failure[0] = e
            } finally {
                finished.set(true)
            }
        }
    }

    @Test
    fun `INV-AC-28460 two concurrent transactions for one identity end with exactly one row`() {
        val t = tenant()
        val date = LocalDate.of(2026, 2, 27)
        val tenantId = TenantId(t)
        connection(t).use { first ->
            connection(t).use { second ->
                val store = JooqPriorEvidenceStore(DSL.using(first, SQLDialect.POSTGRES))
                store.lock(tenantId, "E1", "DOC-1", date)
                (store.find(tenantId, "E1", "DOC-1", date) == null) shouldBe true
                val importer = Importer(second, tenantId, date) { row() }
                val worker = Thread { importer.run() }
                worker.start()
                importer.started.await(10, TimeUnit.SECONDS) shouldBe true
                Thread.sleep(500)
                importer.finished.get() shouldBe false
                store.insert(tenantId, row())
                first.commit()
                worker.join(20_000)
                importer.failure[0] shouldBe null
                importer.sawRow.get() shouldBe true
            }
        }
        scalar("select count(*) from evidence.prior_evidence where tenant_id = ?", t) shouldBe "1"
    }

    private class Stored : ObjectStore {
        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef = error("a refused deletion writes no object")

        override fun get(key: ObjectKey): ByteArray = error("a refused deletion reads no object")
    }

    private class NoAudit : DeletionAudit {
        override fun append(
            tenant: String,
            partitionId: String,
            actorUserId: String,
            tailHashes: List<String>,
        ): String = error("a refused deletion writes no audit entry")
    }

    private fun effective(tenant: UUID): EffectiveRetention {
        val reader =
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ) = ResolvedSetting(key, at, listOf(SelectedOption("YEARS_5")), PolicySource.CatalogueDefault)
            }
        val tenants =
            object : TenantContext {
                override fun current() = TenantId(tenant)

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        return EffectiveRetention(reader, tenants, 5, JsonTierYearsReader())
    }

    @Test
    fun `INV-AC-28460 the retention end of an imported record two years old is its quarter end plus five years`() {
        val t = tenant()
        val date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusYears(2)
        insert(t, row(date = date))
        val partition =
            checkNotNull(
                scalar(
                    "select partition_id from evidence.retention_partition where tenant_id = ? and period_from = ?",
                    t,
                    PriorEvidencePeriodRef.of(date),
                ),
            )
        PriorEvidencePeriodRef.of(date) shouldBe "2024-Q4"
        val calls = mutableListOf<String>()
        val (end, result) =
            runtime(t) { dsl ->
                val records = JooqRetentionRecords(dsl, effective(t), clock)
                records.retentionEnd(t.toString(), partition) to
                    DeleteAtRetentionEnd(
                        records,
                        records,
                        records,
                        records,
                        records,
                        records,
                        NoAudit(),
                        JooqPartitionLifecycle(dsl, Stored(), clock, Duration.ofDays(30)) { calls += it },
                        clock,
                    ).execute(t.toString(), "u-starter", partition)
            }
        end shouldBe Instant.parse("2030-01-01T00:00:00Z")
        result shouldBe DeleteAtRetentionEndResult.RETENTION_NOT_ENDED
        calls shouldBe emptyList()
        scalar("select count(*) from evidence.prior_evidence where tenant_id = ?", t) shouldBe "1"
    }
}
