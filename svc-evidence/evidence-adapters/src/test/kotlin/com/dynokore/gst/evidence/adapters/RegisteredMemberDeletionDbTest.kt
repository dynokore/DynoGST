package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqPriorEvidenceStore
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.migration.PriorEvidenceRow
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEndResult
import com.dynokore.gst.evidence.application.retention.DeletionAudit
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.retention.PriorEvidencePeriodRef
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * INV-AC-28694: retention-end deletion acts on the registered members of a unit (V31), so a unit with only imported
 * records is deleted, and the deletion and the writers of a unit are serialized by row locks on the unit. Real
 * PostgreSQL, the runtime login and two real connections for the concurrent cases; an in-memory object store.
 */
@Suppress("LargeClass") // one DB test class for the one criterion (INV-AC-28694)
class RegisteredMemberDeletionDbTest {
    private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
    private val tenants = mutableListOf<UUID>()
    private val threads = Executors.newCachedThreadPool()

    private fun tenant(): UUID = UUID.randomUUID().also { tenants.add(it) }

    /** Detached tables are standalone until dropped: the shared schema is left as found. */
    @AfterEach
    fun dropDetachedTables() {
        threads.shutdownNow()
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

    /** A runtime-login connection in an open transaction bound to [tenant]; a statement never waits for ever. */
    private fun open(tenant: UUID): Connection =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).also { c ->
            c.createStatement().use { it.execute("set statement_timeout = '${WAIT_SECONDS}s'") }
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
        }

    private fun <T> runtime(
        tenant: UUID,
        block: (DSLContext) -> T,
    ): T =
        open(tenant).use { c ->
            try {
                block(dsl(c)).also { c.commit() }
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private fun dsl(c: Connection): DSLContext = DSL.using(c, SQLDialect.POSTGRES)

    /** One statement as the runtime login bound to [tenant]: the SQLSTATE of a failure, else null. */
    private fun sqlState(
        tenant: UUID,
        sql: String,
        vararg args: Any?,
    ): String? = open(tenant).use { run(it, sql, args) }

    private fun run(
        c: Connection,
        sql: String,
        args: Array<out Any?>,
    ): String? =
        try {
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.execute()
            }
            c.commit()
            null
        } catch (e: SQLException) {
            c.rollback()
            e.sqlState
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

    private fun superuserUpdate(
        sql: String,
        vararg args: Any?,
    ) = EvidenceDb.superuser().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate() shouldBe 1
        }
    }

    private fun insertImported(
        dsl: DSLContext,
        tenant: UUID,
        document: String,
    ) {
        val date = LocalDate.of(2010, 2, 1)
        JooqPriorEvidenceStore(dsl).insert(
            TenantId(tenant),
            PriorEvidenceRow(
                UUID.randomUUID(),
                ENTITY,
                PriorEvidencePeriodRef.of(date),
                document,
                date,
                "ACK-$document",
                "LEGACY",
                null,
                clock.instant(),
                "u-importer",
            ),
        )
    }

    private fun appendAudit(
        dsl: DSLContext,
        tenant: UUID,
    ) {
        ChainWiring
            .appendLedgers(dsl)
            .getValue(RecordKind.AUDIT_LOG)
            .append(TenantId(tenant), EntityRef(ENTITY), PeriodRef(PERIOD), EvidenceType.AUDIT_LOG, "a".toByteArray())
    }

    private fun partitionId(tenant: UUID): String =
        checkNotNull(
            scalar(
                "select partition_id from evidence.retention_partition " +
                    "where tenant_id = ? and entity_ref = ? and period_from = ?",
                tenant,
                ENTITY,
                PERIOD,
            ),
        )

    /** The notice (window closed) and the two distinct approvals of the deletion of the unit of [tenant]. */
    private fun authority(tenant: UUID): String {
        val id = partitionId(tenant)
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        sqlState(
            tenant,
            "insert into evidence.retention_notice values (?, ?, ?, 30, ?)",
            tenant,
            id,
            now.minusDays(NOTICED_DAYS_AGO),
            now.minusDays(CLOSED_DAYS_AGO),
        ) shouldBe null
        listOf("u-approver-1", "u-approver-2").forEach {
            sqlState(
                tenant,
                "insert into evidence.retention_approval values (?, ?, ?, 'retention.delete.approve', ?)",
                tenant,
                id,
                it,
                now,
            ) shouldBe null
        }
        return id
    }

    /** A deletion-ready unit that holds one imported record and nothing else. */
    private fun importedUnit(tenant: UUID): String {
        runtime(tenant) { insertImported(it, tenant, "DOC-1") }
        return authority(tenant)
    }

    private fun unitState(id: String) =
        scalar("select state from evidence.retention_partition where partition_id = ?", id)

    /** `table=state` of every registered member, in table-name order. */
    private fun members(id: String) =
        scalar(
            "select string_agg(chain_table || '=' || state, ',' order by chain_table) " +
                "from evidence.retention_partition_member where partition_id = ?",
            id,
        )

    private class Memory(
        private val onPut: () -> Unit = {},
    ) : ObjectStore {
        val objects = mutableMapOf<String, ByteArray>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            onPut()
            objects[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = objects.getValue(key.value)

        fun text(key: String) = objects.getValue(key).toString(Charsets.UTF_8)
    }

    /** What one deletion did: its archive objects, its observed lifecycle calls and its audit entries. */
    private class Seen(
        val store: Memory = Memory(),
    ) {
        val calls = mutableListOf<String>()
        val audit = Audited()
    }

    private class Audited : DeletionAudit {
        val tailHashes = mutableListOf<List<String>>()

        override fun append(
            tenant: String,
            partitionId: String,
            actorUserId: String,
            tailHashes: List<String>,
        ): String {
            this.tailHashes += tailHashes
            return "audit-1"
        }
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
        val context =
            object : TenantContext {
                override fun current() = TenantId(tenant)

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        return EffectiveRetention(reader, context, 5, JsonTierYearsReader())
    }

    private fun lifecycle(
        dsl: DSLContext,
        store: ObjectStore,
        calls: MutableList<String>,
    ) = JooqPartitionLifecycle(dsl, store, clock, Duration.ofDays(30)) { calls.add(it) }

    /** The whole deletion use case on [c], committed here. */
    private fun delete(
        c: Connection,
        tenant: UUID,
        id: String,
        seen: Seen,
    ): DeleteAtRetentionEndResult {
        val records = JooqRetentionRecords(dsl(c), effective(tenant), clock)
        val result =
            DeleteAtRetentionEnd(
                records,
                records,
                records,
                records,
                records,
                records,
                seen.audit,
                lifecycle(dsl(c), seen.store, seen.calls),
                clock,
            ).execute(tenant.toString(), "u-starter", id)
        c.commit()
        return result
    }

    private fun key(
        tenant: UUID,
        id: String,
        table: String,
    ) = "retention-archive/$tenant/$id/$table.json"

    private fun pid(c: Connection): Int =
        c.createStatement().use { s ->
            s.executeQuery("select pg_backend_pid()").use { rs -> rs.next().let { rs.getInt(1) } }
        }

    /** Returns once the backend [pid] waits on a lock; fails if [work] ends first or the bound passes. */
    private fun awaitLockWait(
        pid: Int,
        work: Future<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (scalar("select wait_event_type from pg_stat_activity where pid = ?", pid) != "Lock") {
            check(!work.isDone) { "the work of backend $pid ended without waiting on a lock" }
            check(System.nanoTime() < deadline) { "backend $pid was not seen waiting on a lock" }
            Thread.onSpinWait()
        }
    }

    private fun <T> Future<T>.bounded(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `INV-AC-28694 a unit with only imported records is deleted through its one registered member`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val seen = Seen()
        open(tenant).use { delete(it, tenant, id, seen) } shouldBe DeleteAtRetentionEndResult.DELETED
        seen.calls shouldBe listOf("detach:prior_evidence", "archive", "drop:prior_evidence")
        seen.store.objects.keys
            .toList() shouldBe listOf(key(tenant, id, "prior_evidence"))
        seen.store.text(key(tenant, id, "prior_evidence")) shouldContain "\"DOC-1\""
        unitState(id) shouldBe "DROPPED"
        members(id) shouldBe "prior_evidence=DROPPED"
        scalar(
            "select tail_hashes::text from evidence.retention_partition where partition_id = ?",
            id,
        ) shouldBe "{\"prior_evidence\": null}"
        seen.audit.tailHashes shouldBe listOf(emptyList())
    }

    @Test
    fun `INV-AC-28694 a unit whose only member is audit_log is deleted through that member`() {
        val tenant = tenant()
        runtime(tenant) { appendAudit(it, tenant) }
        val id = authority(tenant)
        val seen = Seen()
        open(tenant).use { delete(it, tenant, id, seen) } shouldBe DeleteAtRetentionEndResult.DELETED
        seen.calls shouldBe listOf("detach:audit_log", "archive", "drop:audit_log")
        seen.store.objects.keys
            .toList() shouldBe listOf(key(tenant, id, "audit_log"))
        unitState(id) shouldBe "DROPPED"
        members(id) shouldBe "audit_log=DROPPED"
    }

    @Test
    fun `INV-AC-28694 a legal hold on the entity and period of an imported-only unit stops its deletion`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        sqlState(
            tenant,
            "insert into evidence.legal_hold " +
                "(tenant_id, entity_ref, period_ref, reason, status, placed_at, placed_by) " +
                "values (?, ?, ?, 'audit', 'ACTIVE', now(), 'u-hold')",
            tenant,
            ENTITY,
            PERIOD,
        ) shouldBe null
        val seen = Seen()
        open(tenant).use { delete(it, tenant, id, seen) } shouldBe DeleteAtRetentionEndResult.HOLD_IN_FORCE
        seen.calls shouldBe emptyList()
        seen.store.objects.size shouldBe 0
        unitState(id) shouldBe "ATTACHED"
    }

    @Test
    fun `INV-AC-28694 a unit with no registered member is neither detached nor archived`() {
        val tenant = tenant()
        val id = "rp_" + UUID.randomUUID().toString().replace("-", "")
        superuserUpdate(
            "insert into evidence.retention_partition " +
                "(partition_id, tenant_id, entity_ref, period_from, period_to, state) " +
                "values (?, ?, ?, ?, ? || chr(1), 'ATTACHED')",
            id,
            tenant,
            ENTITY,
            PERIOD,
            PERIOD,
        )
        val store = Memory()
        val calls = mutableListOf<String>()
        val failure =
            runCatching {
                runtime(tenant) { lifecycle(it, store, calls).detach(id, "audit-1", clock.instant().minusSeconds(1)) }
            }.exceptionOrNull()
        failure?.message.orEmpty() shouldContain "the registered members of $id are not usable"
        store.objects.size shouldBe 0
        calls shouldBe emptyList()
        superuserUpdate("update evidence.retention_partition set state = 'DETACHED' where partition_id = ?", id)
        sqlState(tenant, "select evidence.mark_partition_archived(?, 'vol-1')", id) shouldBe "55000"
        unitState(id) shouldBe "DETACHED"
    }

    @Test
    fun `INV-AC-28694 the deletion lock is refused without a tenant, for another tenant and for a detached unit`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val lock = "select evidence.lock_retention_partition(?)"
        sqlState(tenant(), lock, id) shouldBe "55000"
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            run(c, lock, arrayOf(id))
        } shouldBe "42501"
        sqlState(tenant, lock, id) shouldBe null
        superuserUpdate("update evidence.retention_partition set state = 'DETACHED' where partition_id = ?", id)
        sqlState(tenant, lock, id) shouldBe "55000"
    }

    @Test
    fun `INV-AC-28694 a record of a transaction open when the deletion starts is archived after it commits`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val seen = Seen()
        open(tenant).use { writer ->
            insertImported(dsl(writer), tenant, "DOC-2")
            open(tenant).use { deleter ->
                val deleterPid = pid(deleter)
                val deletion =
                    threads.submit<DeleteAtRetentionEndResult> {
                        delete(deleter, tenant, id, seen)
                    }
                try {
                    awaitLockWait(deleterPid, deletion)
                } finally {
                    writer.commit()
                }
                deletion.bounded() shouldBe DeleteAtRetentionEndResult.DELETED
            }
        }
        val archived = seen.store.text(key(tenant, id, "prior_evidence"))
        archived shouldContain "\"DOC-1\""
        archived shouldContain "\"DOC-2\""
        unitState(id) shouldBe "DROPPED"
    }

    @Test
    fun `INV-AC-28694 a record written after the deletion holds the unit waits and is then refused`() {
        writeDuringDeletion { dsl, tenant -> insertImported(dsl, tenant, "DOC-2") }
    }

    @Test
    fun `INV-AC-28694 a new member created after the deletion holds the unit waits and is then refused`() {
        writeDuringDeletion { dsl, tenant -> appendAudit(dsl, tenant) }
    }

    /** The deletion is held inside its transaction, after the lock, in the put of its archive object. */
    private fun writeDuringDeletion(write: (DSLContext, UUID) -> Unit) {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val inPut = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store =
            Memory {
                inPut.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the put was not released" }
            }
        open(tenant).use { deleter ->
            open(tenant).use { writer ->
                val deletion =
                    threads.submit<DeleteAtRetentionEndResult> {
                        delete(deleter, tenant, id, Seen(store))
                    }
                val writing: Future<String?>
                try {
                    inPut.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    val writerPid = pid(writer)
                    writing = threads.submit<String?> { refusalOf(writer) { write(it, tenant) } }
                    awaitLockWait(writerPid, writing)
                } finally {
                    release.countDown()
                }
                deletion.bounded() shouldBe DeleteAtRetentionEndResult.DELETED
                writing.bounded() shouldBe "55000"
            }
        }
        assertNothingLeft(tenant, id)
    }

    /** Runs [write] on [writer] and commits: the SQLSTATE of its refusal, else null. */
    private fun refusalOf(
        writer: Connection,
        write: (DSLContext) -> Unit,
    ): String? =
        try {
            write(dsl(writer))
            writer.commit()
            null
        } catch (e: DataAccessException) {
            writer.rollback()
            e.sqlState()
        }

    /** No imported row, no partition table and no attached member of the deleted unit remains. */
    private fun assertNothingLeft(
        tenant: UUID,
        id: String,
    ) {
        scalar("select count(*) from evidence.prior_evidence where tenant_id = ?", tenant) shouldBe "0"
        scalar("select count(*) from pg_class where relname like ?", "%_$id") shouldBe "0"
        unitState(id) shouldBe "DROPPED"
        members(id) shouldBe "prior_evidence=DROPPED"
    }

    @Test
    fun `INV-AC-28694 the archive mark is refused while a registered member of a detached unit is attached`() {
        val tenant = tenant()
        runtime(tenant) {
            appendAudit(it, tenant)
            insertImported(it, tenant, "DOC-1")
        }
        val id = partitionId(tenant)
        members(id) shouldBe "audit_log=ATTACHED,prior_evidence=ATTACHED"
        val detachMember =
            "update evidence.retention_partition_member set state = 'DETACHED' " +
                "where partition_id = ? and chain_table = ?"
        superuserUpdate("update evidence.retention_partition set state = 'DETACHED' where partition_id = ?", id)
        superuserUpdate(detachMember, id, "audit_log")
        val mark = "select evidence.mark_partition_archived(?, 'vol-1')"
        sqlState(tenant, mark, id) shouldBe "55000"
        unitState(id) shouldBe "DETACHED"
        members(id) shouldBe "audit_log=DETACHED,prior_evidence=ATTACHED"
        superuserUpdate(detachMember, id, "prior_evidence")
        sqlState(tenant, mark, id) shouldBe null
        unitState(id) shouldBe "ARCHIVED"
    }

    private companion object {
        const val ENTITY = "E1"
        const val PERIOD = "2010-Q1"
        const val WAIT_SECONDS = 30L
        const val NOTICED_DAYS_AGO = 90L
        const val CLOSED_DAYS_AGO = 60L
    }
}
