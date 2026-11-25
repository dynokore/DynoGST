package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionLifecycle
import com.dynokore.gst.evidence.adapters.persistence.JooqPriorEvidenceStore
import com.dynokore.gst.evidence.adapters.persistence.JooqRetentionRecords
import com.dynokore.gst.evidence.adapters.retention.JsonTierYearsReader
import com.dynokore.gst.evidence.application.migration.ImportPriorEvidence
import com.dynokore.gst.evidence.application.migration.ImportPriorEvidenceCommand
import com.dynokore.gst.evidence.application.migration.ImportResult
import com.dynokore.gst.evidence.application.migration.PriorEvidenceRow
import com.dynokore.gst.evidence.application.migration.PriorSubmissionRecordCommand
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
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * records is deleted, and the deletion and the writers of a unit are serialized by row locks on the unit, in one lock
 * order with the importer (unit rows first, ascending), so none of them gets a deadlock (SQLSTATE 40P01). Real
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
        val date = DATE_A
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

    private fun partitionId(
        tenant: UUID,
        period: String = PERIOD,
    ): String =
        checkNotNull(
            scalar(
                "select partition_id from evidence.retention_partition " +
                    "where tenant_id = ? and entity_ref = ? and period_from = ?",
                tenant,
                ENTITY,
                period,
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
        onAudit: () -> Unit = {},
    ) {
        val calls = mutableListOf<String>()
        val audit = Audited(onAudit)
    }

    private class Audited(
        private val onAudit: () -> Unit,
    ) : DeletionAudit {
        val tailHashes = mutableListOf<List<String>>()

        override fun append(
            tenant: String,
            partitionId: String,
            actorUserId: String,
            tailHashes: List<String>,
        ): String {
            this.tailHashes += tailHashes
            onAudit()
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

    /** The importer: the whole use case over the store on [c], not committed here. */
    private fun import(
        c: Connection,
        tenant: UUID,
        vararg records: Pair<String, LocalDate>,
    ): ImportResult =
        ImportPriorEvidence(
            authorizer = { _, _, _, _ -> true },
            store = JooqPriorEvidenceStore(dsl(c)),
            events = { _, _ -> },
            clock = clock,
            ids = UUID::randomUUID,
        ).execute(
            TenantId(tenant),
            Principal("u-importer", PrincipalKind.HUMAN),
            ENTITY,
            ImportPriorEvidenceCommand(
                "LEGACY",
                records.map { (document, date) -> PriorSubmissionRecordCommand(document, date, "ACK-$document") },
            ),
        )

    private fun rowsIn(
        tenant: UUID,
        period: String,
    ) = scalar("select count(*) from evidence.prior_evidence where tenant_id = ? and period_ref = ?", tenant, period)

    private fun unitsIn(
        tenant: UUID,
        period: String,
    ) = scalar(
        "select count(*) from evidence.retention_partition where tenant_id = ? and period_from = ?",
        tenant,
        period,
    )

    /** [check] runs while [hold] keeps one deletion inside its transaction; the deletion must then end DELETED. */
    private fun whileDeletionIsHeld(
        tenant: UUID,
        id: String,
        hold: Hold,
        seen: Seen,
        check: () -> () -> Unit,
    ) {
        open(tenant).use { deleter ->
            val deletion = threads.submit<DeleteAtRetentionEndResult> { delete(deleter, tenant, id, seen) }
            val afterwards: () -> Unit
            try {
                hold.reached.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                afterwards = check()
            } finally {
                hold.release.countDown()
            }
            deletion.bounded() shouldBe DeleteAtRetentionEndResult.DELETED
            afterwards()
        }
    }

    /** Blocks the thread that calls [stop] until the test releases it. */
    private class Hold {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun stop() {
            reached.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the deletion was not released" }
        }
    }

    /** Starts [work] on [c] in another thread and returns once it is seen waiting on a lock. */
    private fun <T> startWaiting(
        c: Connection,
        work: () -> T,
    ): Future<T> {
        val backend = pid(c)
        val started = threads.submit<T> { work() }
        awaitLockWait(backend, started)
        return started
    }

    /** Starts the deletion of [id] while [first] is open, sees it wait, then commits [first]: DELETED. */
    private fun deleteBehind(
        first: Connection,
        tenant: UUID,
        id: String,
        seen: Seen,
    ) {
        open(tenant).use { deleter ->
            val deletion: Future<DeleteAtRetentionEndResult>
            try {
                deletion = startWaiting(deleter) { delete(deleter, tenant, id, seen) }
            } finally {
                first.commit()
            }
            deletion.bounded() shouldBe DeleteAtRetentionEndResult.DELETED
        }
    }

    @Test
    fun `INV-AC-28694 (i) an import of the same unit that came first is archived by the waiting deletion`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val seen = Seen()
        open(tenant).use { importer ->
            import(importer, tenant, "DOC-2" to DATE_A).shouldBeInstanceOf<ImportResult.Imported>()
            deleteBehind(importer, tenant, id, seen)
        }
        val archived = seen.store.text(key(tenant, id, "prior_evidence"))
        archived shouldContain "\"DOC-1\""
        archived shouldContain "\"DOC-2\""
        unitState(id) shouldBe "DROPPED"
    }

    @Test
    fun `INV-AC-28694 (j) an import of a unit under deletion waits and is then refused`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val hold = Hold()
        open(tenant).use { importer ->
            whileDeletionIsHeld(tenant, id, hold, Seen(Memory { hold.stop() })) {
                val importing =
                    startWaiting(importer) { refusalOf(importer) { import(importer, tenant, "DOC-2" to DATE_A) } }
                return@whileDeletionIsHeld { importing.bounded() shouldBe "55000" }
            }
        }
        assertNothingLeft(tenant, id)
    }

    @Test
    fun `INV-AC-28694 (k1) an import into a new unit waits for the deletion of another and then commits`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val hold = Hold()
        open(tenant).use { importer ->
            whileDeletionIsHeld(tenant, id, hold, Seen(Memory { hold.stop() })) {
                val importing =
                    startWaiting(importer) { refusalOf(importer) { import(importer, tenant, "DOC-B" to DATE_B) } }
                return@whileDeletionIsHeld { importing.bounded() shouldBe null }
            }
        }
        unitState(id) shouldBe "DROPPED"
        members(partitionId(tenant, PERIOD_B)) shouldBe "prior_evidence=ATTACHED"
        rowsIn(tenant, PERIOD_B) shouldBe "1"
    }

    @Test
    fun `INV-AC-28694 (k2) a refused import of a new unit and a deleted one leaves nothing of the new unit`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val hold = Hold()
        open(tenant).use { importer ->
            whileDeletionIsHeld(tenant, id, hold, Seen(Memory { hold.stop() })) {
                val importing =
                    startWaiting(importer) {
                        refusalOf(importer) { import(importer, tenant, "DOC-B" to DATE_B, "DOC-2" to DATE_A) }
                    }
                return@whileDeletionIsHeld { importing.bounded() shouldBe "55000" }
            }
        }
        unitsIn(tenant, PERIOD_B) shouldBe "0"
        scalar("select count(*) from evidence.retention_partition_member where tenant_id = ?", tenant) shouldBe "1"
        scalar(
            "select count(*) from pg_class where relispartition and relkind = 'r' " +
                "and pg_get_expr(relpartbound, oid) like ?",
            "%$tenant%",
        ) shouldBe "0"
        assertNothingLeft(tenant, id)
    }

    @Test
    fun `INV-AC-28694 (k3) a deletion waits for an import of a new unit that came first and both succeed`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        open(tenant).use { importer ->
            import(importer, tenant, "DOC-B" to DATE_B).shouldBeInstanceOf<ImportResult.Imported>()
            deleteBehind(importer, tenant, id, Seen())
        }
        unitState(id) shouldBe "DROPPED"
        members(partitionId(tenant, PERIOD_B)) shouldBe "prior_evidence=ATTACHED"
        rowsIn(tenant, PERIOD_B) shouldBe "1"
    }

    @Test
    fun `INV-AC-28694 (l) an import locks its units in ascending order whatever order its records have`() {
        val tenant = tenant()
        open(tenant).use {
            import(it, tenant, "DOC-1" to DATE_A, "DOC-2" to DATE_Q2)
            it.commit()
        }
        val later = partitionId(tenant, PERIOD_Q2)
        open(tenant).use { holder ->
            lockImportUnit(holder, tenant, PERIOD)
            open(tenant).use { importer ->
                val importing: Future<String?>
                try {
                    importing =
                        startWaiting(importer) {
                            refusalOf(importer) { import(importer, tenant, "DOC-3" to DATE_Q2, "DOC-4" to DATE_A) }
                        }
                    lockableNow(later)
                } finally {
                    holder.commit()
                }
                importing.bounded() shouldBe null
            }
        }
        rowsIn(tenant, PERIOD) shouldBe "2"
        rowsIn(tenant, PERIOD_Q2) shouldBe "2"
    }

    @Test
    fun `INV-AC-28694 (m) a chain writer waits for a deletion that has read the record tables and is refused`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val hold = Hold()
        open(tenant).use { writer ->
            whileDeletionIsHeld(tenant, id, hold, Seen(onAudit = { hold.stop() })) {
                val writing = startWaiting(writer) { refusalOf(writer) { appendAudit(it, tenant) } }
                return@whileDeletionIsHeld { writing.bounded() shouldBe "55000" }
            }
        }
        assertNothingLeft(tenant, id)
    }

    @Test
    fun `INV-AC-28694 (n) the deletion's first lock refuses only a missing tenant and locks only its own unit`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val hold = "select evidence.hold_retention_partition(?)"
        unbound(hold, id) shouldBe "42501"
        open(tenant()).use { other ->
            other.prepareStatement(hold).use {
                it.setString(1, id)
                it.execute()
            }
            lockableNow(id)
        }
        superuserUpdate("update evidence.retention_partition set state = 'DETACHED' where partition_id = ?", id)
        sqlState(tenant, hold, id) shouldBe null
    }

    @Test
    fun `INV-AC-28694 (n) the import lock refuses another tenant, a bad range and a unit that is not attached`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val whole = "select evidence.lock_import_unit(?, ?, ?, ? || chr(1))"
        sqlState(tenant(), whole, tenant, ENTITY, PERIOD, PERIOD) shouldBe "42501"
        unbound(whole, tenant, ENTITY, PERIOD, PERIOD) shouldBe "42501"
        EvidenceDb.connect(EvidenceDb.MIGRATE, EvidenceDb.MIGRATE_PASSWORD).use { c ->
            c.autoCommit = false
            run(c, whole, arrayOf<Any>(tenant, ENTITY, PERIOD, PERIOD))
        } shouldBe "42501"
        sqlState(tenant, "select evidence.lock_import_unit(?, ?, ?, ?)", tenant, ENTITY, PERIOD, PERIOD) shouldBe
            "22023"
        sqlState(tenant, whole, tenant, ENTITY, PERIOD_B, PERIOD_B) shouldBe null
        unitsIn(tenant, PERIOD_B) shouldBe "1"
        val created = partitionId(tenant, PERIOD_B)
        unitState(created) shouldBe "ATTACHED"
        members(created) shouldBe null
        superuserUpdate("update evidence.retention_partition set state = 'DETACHED' where partition_id = ?", id)
        sqlState(tenant, whole, tenant, ENTITY, PERIOD, PERIOD) shouldBe "55000"
        assertImportLockPrivileges()
    }

    /** The owner of the import lock gained INSERT on the unit registry and nothing else near it. */
    private fun assertImportLockPrivileges() {
        fun table(
            name: String,
            privilege: String,
        ) = scalar("select has_table_privilege(?, ?, ?)::text", EvidenceDb.SYS, "evidence.$name", privilege)

        fun updatable(column: String) =
            scalar(
                "select has_column_privilege(?, 'evidence.retention_partition', ?, 'UPDATE')::text",
                EvidenceDb.SYS,
                column,
            )
        table("retention_partition", "INSERT") shouldBe "true"
        table("retention_partition", "DELETE") shouldBe "false"
        table("retention_partition", "TRUNCATE") shouldBe "false"
        table("retention_partition_member", "INSERT") shouldBe "false"
        updatable("tenant_id") shouldBe "false"
        updatable("partition_id") shouldBe "false"
        scalar(
            "select string_agg(policyname, ',' order by policyname) from pg_policies " +
                "where schemaname = 'evidence' and tablename = 'retention_partition'",
        ) shouldBe "tenant_isolation"
    }

    @Test
    fun `INV-AC-28694 (o) detach alone waits for a writer of the unit and archives its record`() {
        val tenant = tenant()
        val id = importedUnit(tenant)
        val seen = Seen()
        open(tenant).use { writer ->
            insertImported(dsl(writer), tenant, "DOC-2")
            open(tenant).use { deleter ->
                val detaching: Future<Unit>
                try {
                    detaching =
                        startWaiting(deleter) {
                            lifecycle(dsl(deleter), seen.store, seen.calls)
                                .detach(id, "audit-1", clock.instant().minusSeconds(1))
                            deleter.commit()
                        }
                } finally {
                    writer.commit()
                }
                detaching.bounded()
            }
        }
        val archived = seen.store.text(key(tenant, id, "prior_evidence"))
        archived shouldContain "\"DOC-1\""
        archived shouldContain "\"DOC-2\""
    }

    @Test
    fun `INV-AC-28694 (p) a chain writer waits for an import that is creating the unit and then succeeds`() {
        val tenant = tenant()
        open(tenant).use { holder ->
            lockImportUnit(holder, tenant, PERIOD)
            open(tenant).use { writer ->
                val writing: Future<String?>
                try {
                    writing = startWaiting(writer) { refusalOf(writer) { appendAudit(it, tenant) } }
                } finally {
                    holder.commit()
                }
                writing.bounded() shouldBe null
            }
        }
        unitsIn(tenant, PERIOD) shouldBe "1"
        members(partitionId(tenant)) shouldBe "audit_log=ATTACHED"
    }

    @Test
    fun `INV-AC-28694 (q) the import lock of a unit is exclusive`() {
        val tenant = tenant()
        importedUnit(tenant)
        open(tenant).use { holder ->
            lockImportUnit(holder, tenant, PERIOD)
            open(tenant).use { second ->
                val locking: Future<Unit>
                try {
                    locking =
                        startWaiting(second) {
                            lockImportUnit(second, tenant, PERIOD)
                            second.commit()
                        }
                } finally {
                    holder.commit()
                }
                locking.bounded()
            }
        }
    }

    @Test
    fun `INV-AC-28694 an import locks all its units before it creates a member`() {
        val tenant = tenant()
        open(tenant).use { holder ->
            lockImportUnit(holder, tenant, PERIOD_Q2)
            open(tenant).use { importer ->
                val importerPid = pid(importer)
                val importing: Future<String?>
                try {
                    importing =
                        startWaiting(importer) {
                            refusalOf(importer) { import(importer, tenant, "DOC-1" to DATE_A, "DOC-2" to DATE_Q2) }
                        }
                    // Creating the member of the first unit would hold this on the parent while the second waits.
                    scalar(
                        "select count(*) from pg_locks where pid = ? and mode = 'AccessExclusiveLock' " +
                            "and relation = 'evidence.prior_evidence'::regclass",
                        importerPid,
                    ) shouldBe "0"
                } finally {
                    holder.commit()
                }
                importing.bounded() shouldBe null
            }
        }
        rowsIn(tenant, PERIOD) shouldBe "1"
        rowsIn(tenant, PERIOD_Q2) shouldBe "1"
    }

    private fun lockImportUnit(
        c: Connection,
        tenant: UUID,
        period: String,
    ) {
        c.prepareStatement("select evidence.lock_import_unit(?, ?, ?, ? || chr(1))").use { st ->
            listOf<Any>(tenant, ENTITY, period, period).forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    /** The unit row [id] can be locked at once: nobody else holds it. The lock is given back. */
    private fun lockableNow(id: String) =
        EvidenceDb.superuser().use { c ->
            c.autoCommit = false
            val sql = "select 1 from evidence.retention_partition where partition_id = ? for update nowait"
            c.prepareStatement(sql).use { st ->
                st.setString(1, id)
                st.executeQuery().use { it.next() shouldBe true }
            }
            c.rollback()
        }

    /** One statement as the runtime login with no tenant binding: the SQLSTATE of a failure, else null. */
    private fun unbound(
        sql: String,
        vararg args: Any?,
    ): String? =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            run(c, sql, args)
        }

    private companion object {
        const val ENTITY = "E1"
        const val PERIOD = "2010-Q1"
        const val PERIOD_B = "2009-Q4"
        const val PERIOD_Q2 = "2010-Q2"
        val DATE_A: LocalDate = LocalDate.of(2010, 2, 1)

        /** A quarter before [DATE_A]'s, so its unit row is locked before that of A. */
        val DATE_B: LocalDate = LocalDate.of(2009, 11, 1)
        val DATE_Q2: LocalDate = LocalDate.of(2010, 5, 1)
        const val WAIT_SECONDS = 30L
        const val NOTICED_DAYS_AGO = 90L
        const val CLOSED_DAYS_AGO = 60L
    }
}
