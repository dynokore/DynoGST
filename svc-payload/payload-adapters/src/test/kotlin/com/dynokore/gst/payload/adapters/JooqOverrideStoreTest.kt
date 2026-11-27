package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.domain.override.OverrideStatus
import gst.build.konsist.MigrationLint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** The override store against the real database as `payload_runtime` with a signed binding (INV-T-10713). */
class JooqOverrideStoreTest {
    private companion object {
        const val FORCED_QUERY =
            "select relrowsecurity and relforcerowsecurity from pg_class where oid = to_regclass(?)"
    }

    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val doc = UUID.randomUUID()

    private fun <T> bound(
        tenant: UUID,
        commit: Boolean = true,
        block: (JooqOverrideStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(JooqOverrideStore(DSL.using(c, SQLDialect.POSTGRES)) { tenant })
            } finally {
                if (commit) c.commit() else c.rollback()
            }
        }

    private fun pending(
        code: String = "PAY-011",
        version: Int = 1,
        by: String = "A1",
    ) = NewOverride(doc, version, code, "register lag", OverrideStatus.PENDING, by, null)

    @Test
    fun `a pending row is stored with its columns and found`() {
        val row = bound(t1) { it.insert(pending()) }!!
        row.status shouldBe OverrideStatus.PENDING
        row.requestedBy shouldBe "A1"
        row.releasedBy shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") } shouldBe row
    }

    @Test
    fun `a second insert of the same key stores nothing`() {
        bound(t1) { it.insert(pending()) } shouldNotBe null
        bound(t1) { it.insert(pending(by = "A2")) } shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.requestedBy shouldBe "A1"
    }

    @Test
    fun `the same key under another version or tenant is another row and another tenant sees nothing`() {
        bound(t1) { it.insert(pending()) } shouldNotBe null
        bound(t1) { it.insert(pending(version = 2)) } shouldNotBe null
        bound(t2) { it.insert(pending()) } shouldNotBe null
        bound(t2) { it.find(doc, 1, "PAY-011") }!!.requestedBy shouldBe "A1"
        bound(t2) { it.release(doc, 1, "PAY-011", "A9") }!!.releasedBy shouldBe "A9"
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `a released row cannot be released again`() {
        bound(t1) { it.insert(pending()) }
        bound(t1) { it.release(doc, 1, "PAY-011", "A2") }!!.releasedBy shouldBe "A2"
        bound(t1) { it.release(doc, 1, "PAY-011", "A3") } shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.releasedBy shouldBe "A2"
    }

    @Test
    fun `a row released in one step carries both users`() {
        val row = bound(t1) { it.insert(NewOverride(doc, 1, "PAY-011", "r", OverrideStatus.RELEASED, "A1", "A1")) }!!
        row.status shouldBe OverrideStatus.RELEASED
        row.releasedBy shouldBe "A1"
        (row.releasedAt != null) shouldBe true
    }

    @Test
    fun `two simultaneous approvals release once and the loser changes no row`() {
        bound(t1) { it.insert(pending()) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val results: List<StoredOverride?>
        try {
            val jobs =
                listOf("A2", "A3").map { approver ->
                    pool.submit<StoredOverride?> {
                        bound(t1) { s ->
                            barrier.await(30, TimeUnit.SECONDS)
                            s.release(doc, 1, "PAY-011", approver)
                        }
                    }
                }
            results = jobs.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        results.count { it != null } shouldBe 1
        results.count { it == null } shouldBe 1
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.releasedBy shouldBe results.single { it != null }!!.releasedBy
    }

    @Test
    fun `entries are ordered by request time then code and page after a cursor`() {
        bound(t1) { it.insert(pending("PAY-011")) }
        bound(t1) { it.insert(pending("PAY-012")) }
        bound(t1) { it.insert(pending("PAY-013")) }
        val all = bound(t1) { it.entries(doc, 1, null, 10) }
        all.map { it.code }.toSet() shouldBe setOf("PAY-011", "PAY-012", "PAY-013")
        val first = bound(t1) { it.entries(doc, 1, null, 2) }
        first.size shouldBe 2
        val rest = bound(t1) { it.entries(doc, 1, first.last().requestedAt to first.last().code, 10) }
        (first + rest).map { it.code } shouldBe all.map { it.code }
        bound(t1) { it.entries(doc, 2, null, 10) } shouldBe emptyList()
    }

    @Test
    fun `a status outside the enum is refused by the database`() {
        PayloadDb.superuser().use { c ->
            c.createStatement().use { s ->
                shouldThrow<java.sql.SQLException> {
                    s.execute(
                        "insert into payload.validation_override " +
                            "(tenant_id, doc_uuid, version, code, reason, status, requested_by) " +
                            "values ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 1, 'PAY-011', 'r', " +
                            "'SUPERSEDED', 'A1')",
                    )
                }
            }
        }
    }

    private fun immediate(
        code: String,
        version: Int = 1,
        docUuid: UUID = doc,
    ) = NewOverride(docUuid, version, code, "register lag", OverrideStatus.RELEASED, "A1", "A1")

    private fun numbers(
        tenant: UUID = t1,
        docUuid: UUID = doc,
    ) = bound(tenant) { s -> s.ofDocument(docUuid).map { Triple(it.version, it.code, it.releaseSequence) } }

    @Test
    fun `INV-AC-29372 releases at once and by approval are numbered 1 to n, pending rows not`() {
        bound(t1) { it.insert(pending("PAY-011")) }!!.releaseSequence shouldBe null
        bound(t1) { it.insert(pending("PAY-012")) }!!.releaseSequence shouldBe null
        bound(t1) { it.insert(immediate("PAY-013")) }!!.releaseSequence shouldBe 1
        bound(t1) { it.release(doc, 1, "PAY-012", "A2") }!!.releaseSequence shouldBe 2
        bound(t1) { it.insert(immediate("PAY-014")) }!!.releaseSequence shouldBe 3
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.releaseSequence shouldBe null
        bound(t1) { it.release(doc, 1, "PAY-011", "A2") }!!.releaseSequence shouldBe 4
        bound(t1) { s -> s.entries(doc, 1, null, 10).map { it.releaseSequence!! }.sorted() } shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `INV-AC-29372 lost, rolled-back and repeated releases consume and change no number`() {
        bound(t1) { it.insert(pending("PAY-011")) }
        bound(t1) { it.insert(immediate("PAY-012")) }!!.releaseSequence shouldBe 1
        bound(t1) { it.insert(immediate("PAY-012")) } shouldBe null
        bound(t1) { it.insert(immediate("PAY-011")) } shouldBe null
        bound(t1, commit = false) { it.release(doc, 1, "PAY-011", "A2") }!!.releaseSequence shouldBe 2
        bound(t1, commit = false) { it.insert(immediate("PAY-013")) }!!.releaseSequence shouldBe 2
        bound(t1) { it.release(doc, 1, "PAY-011", "A2") }!!.releaseSequence shouldBe 2
        bound(t1) { it.release(doc, 1, "PAY-011", "A3") } shouldBe null
        bound(t1) { it.release(doc, 1, "PAY-012", "A3") } shouldBe null
        bound(t1) { it.insert(immediate("PAY-013")) }!!.releaseSequence shouldBe 3
        numbers() shouldBe listOf(Triple(1, "PAY-012", 1), Triple(1, "PAY-011", 2), Triple(1, "PAY-013", 3))
    }

    @Test
    fun `INV-AC-29372 another version and another tenant count from 1`() {
        bound(t1) { it.insert(immediate("PAY-011")) }!!.releaseSequence shouldBe 1
        bound(t1) { it.insert(immediate("PAY-012")) }!!.releaseSequence shouldBe 2
        bound(t1) { it.insert(immediate("PAY-011", version = 2)) }!!.releaseSequence shouldBe 1
        bound(t1) { it.insert(pending("PAY-012", version = 3)) }
        bound(t1) { it.release(doc, 3, "PAY-012", "A2") }!!.releaseSequence shouldBe 1
        bound(t2) { it.insert(immediate("PAY-013")) }!!.releaseSequence shouldBe 1
    }

    @Test
    fun `INV-AC-29372 of two concurrent approvals the first to lock has 1 and the other 2`() {
        bound(t1) { it.insert(pending("PAY-011")) }
        bound(t1) { it.insert(pending("PAY-012")) }
        val (first, second) =
            overlapping(
                t1,
                { it.release(doc, 1, "PAY-012", "A2")!!.releaseSequence },
                { it.release(doc, 1, "PAY-011", "A3")!!.releaseSequence },
            )
        first shouldBe 1
        second shouldBe 2
    }

    @Test
    fun `INV-AC-29372 an immediate and an approved release at once number in lock order`() {
        bound(t1) { it.insert(pending("PAY-012")) }
        overlapping(
            t1,
            { it.insert(immediate("PAY-011"))!!.releaseSequence },
            { it.release(doc, 1, "PAY-012", "A2")!!.releaseSequence },
        ) shouldBe (1 to 2)
        bound(t1) { it.insert(pending("PAY-013")) }
        overlapping(
            t1,
            { it.release(doc, 1, "PAY-013", "A2")!!.releaseSequence },
            { it.insert(immediate("PAY-014"))!!.releaseSequence },
        ) shouldBe (3 to 4)
    }

    @Test
    fun `INV-AC-29372 releases on two versions serialize and the second lists both before its commit`() {
        bound(t1) { it.insert(pending("PAY-011", version = 1)) }
        bound(t1) { it.insert(pending("PAY-011", version = 2)) }
        val (_, listed) =
            overlapping(
                t1,
                { it.release(doc, 2, "PAY-011", "A2") },
                { store ->
                    store.release(doc, 1, "PAY-011", "A3")
                    store.ofDocument(doc).map { it.version to it.releaseSequence }
                },
            )
        listed shouldBe listOf(1 to 1, 2 to 1)
    }

    @Test
    fun `INV-AC-29372 the first to lock has 1 although its release time is later`() {
        bound(t1) { it.insert(pending("PAY-011")) }
        bound(t1) { it.insert(pending("PAY-012")) }
        OpenTransaction(t1).use { startedFirst ->
            // the transaction of startedFirst has already run statements, so its now() is the earlier one
            Thread.sleep(20)
            OpenTransaction(t1).use { lockedFirst ->
                lockedFirst.store.release(doc, 1, "PAY-012", "A2")!!.releaseSequence shouldBe 1
                lockedFirst.commit()
            }
            startedFirst.store.release(doc, 1, "PAY-011", "A3")!!.releaseSequence shouldBe 2
            startedFirst.commit()
        }
        val rows = bound(t1) { it.ofDocument(doc) }
        rows.map { it.code to it.releaseSequence } shouldBe listOf("PAY-012" to 1, "PAY-011" to 2)
        rows[0].releasedAt!!.isAfter(rows[1].releasedAt) shouldBe true
    }

    @Test
    fun `INV-AC-29372 a document lists its numbered releases only, by version, sequence`() {
        val otherDoc = UUID.randomUUID()
        unnumberedRelease(t1, doc, "PAY-019")
        bound(t1) { it.insert(immediate("PAY-013", version = 2)) }
        bound(t1) { it.insert(immediate("PAY-013", version = 1)) }!!.releaseSequence shouldBe 1
        bound(t1) { it.insert(immediate("PAY-011", version = 2)) }
        bound(t1) { it.insert(immediate("PAY-011", version = 1)) }
        bound(t1) { it.insert(pending("PAY-012")) }
        bound(t1) { it.insert(immediate("PAY-014", docUuid = otherDoc)) }!!.releaseSequence shouldBe 1
        bound(t2) { it.insert(immediate("PAY-015")) }!!.releaseSequence shouldBe 1
        numbers() shouldBe
            listOf(
                Triple(1, "PAY-013", 1),
                Triple(1, "PAY-011", 2),
                Triple(2, "PAY-013", 1),
                Triple(2, "PAY-011", 2),
            )
        numbers(docUuid = otherDoc) shouldBe listOf(Triple(1, "PAY-014", 1))
        numbers(tenant = t2) shouldBe listOf(Triple(1, "PAY-015", 1))
        bound(t1) { it.find(doc, 1, "PAY-019") }!!.releaseSequence shouldBe null
    }

    @Test
    fun `INV-AC-29372 the database refuses a number below 1, pending or repeated`() {
        refusal(t1, doc, "PAY-011", "RELEASED", 1) shouldBe null
        refusal(t1, doc, "PAY-012", "RELEASED", 1) shouldBe "23505"
        refusal(t1, doc, "PAY-013", "RELEASED", 0) shouldBe "23514"
        refusal(t1, doc, "PAY-014", "PENDING", 2) shouldBe "23514"
        refusal(t1, doc, "PAY-015", "PENDING", null) shouldBe null
        refusal(t1, doc, "PAY-016", "RELEASED", null) shouldBe null
        refusal(t1, doc, "PAY-017", "RELEASED", null) shouldBe null
    }

    private fun migration() =
        File("src/main/resources/db/migration")
            .listFiles()!!
            .single { it.name.contains("validation_override") }
            .readText()

    private fun violations(sql: String) = MigrationLint.lint("probe.sql", listOf(1 to sql))

    @Test
    fun `the migration passes the lint and a copy without tenant_id fails it`() {
        violations(migration()) shouldBe emptyList()
        violations(migration().replace("    tenant_id uuid not null,\n", "")).isNotEmpty() shouldBe true
    }

    private fun forced(
        table: String,
        create: String? = null,
    ): Boolean =
        PayloadDb.superuser().use { c ->
            c.autoCommit = false
            try {
                create?.let { c.createStatement().use { s -> s.execute(it) } }
                c.prepareStatement(FORCED_QUERY).use { p ->
                    p.setString(1, table)
                    p.executeQuery().use { r -> r.next() && r.getBoolean(1) }
                }
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `the table is under forced row security and a copy without it is detected by the same query`() {
        forced("payload.validation_override") shouldBe true
        forced("payload.validation_override") shouldBe true
        // a copy of the table without FORCE, created and rolled back, is reported as not forced
        forced(
            "payload.probe_override",
            "create table payload.probe_override (id int, tenant_id uuid not null); " +
                "alter table payload.probe_override enable row level security",
        ) shouldBe false
    }

    /** Applies [sql] as `probe_override` in a rolled-back transaction, then runs the CS-19 rule of TenantTablesTest. */
    private fun probeFindings(sql: String): List<String> =
        PayloadDb.superuser().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute(sql.replace("validation_override", "probe_override")) }
                TenantTableRule.findings(c).filter { it.startsWith("probe_override:") }
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `the unmodified migration passes the tenant table rule and a copy without the forced policy fails it`() {
        probeFindings(migration()) shouldBe emptyList()
        val noForce = migration().replace("ALTER TABLE payload.validation_override FORCE ROW LEVEL SECURITY;\n", "")
        (noForce != migration()) shouldBe true
        probeFindings(noForce).size shouldBe 1
        val noPolicy = migration().replace(Regex("create policy[\\s\\S]*?;\n"), "")
        (noPolicy != migration()) shouldBe true
        probeFindings(noPolicy).size shouldBe 1
    }

    @Test
    fun `the signed binding policy is on the table`() {
        PayloadDb.superuser().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from pg_policies " +
                            "where tablename = 'validation_override' and qual like '%bound_tenant%'",
                    ).use { r ->
                        r.next()
                        r.getInt(1) shouldBe 1
                    }
            }
        }
    }
}

/** A transaction of [tenant] held open by the test; closing it without a commit rolls it back. */
private class OpenTransaction(
    tenant: UUID,
) : AutoCloseable {
    private val connection: Connection = PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD)
    val pid: Int
    val store: JooqOverrideStore

    init {
        connection.autoCommit = false
        SignedBindings.bind(connection, tenant)
        pid =
            connection.createStatement().use { s ->
                s.executeQuery("select pg_backend_pid()").use { r ->
                    r.next()
                    r.getInt(1)
                }
            }
        store = JooqOverrideStore(DSL.using(connection, SQLDialect.POSTGRES)) { tenant }
    }

    fun commit() = connection.commit()

    fun rollback() = connection.rollback()

    override fun close() {
        connection.rollback()
        connection.close()
    }
}

/** Whether the backend [pid] is waiting for an advisory lock. */
private fun waitsForAdvisoryLock(pid: Int): Boolean =
    PayloadDb.superuser().use { c ->
        c
            .prepareStatement(
                "select exists (select 1 from pg_locks " +
                    "where pid = ? and locktype = 'advisory' and not granted)",
            ).use { p ->
                p.setInt(1, pid)
                p.executeQuery().use { r -> r.next() && r.getBoolean(1) }
            }
    }

/** Fails unless [job], run on the backend [pid], comes to wait for an advisory lock before it ends. */
private fun awaitBlocked(
    job: Future<*>,
    pid: Int,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!waitsForAdvisoryLock(pid)) {
        job.isDone shouldBe false
        (System.nanoTime() < deadline) shouldBe true
        Thread.sleep(20)
    }
}

/** Runs [first] uncommitted, starts [second], sees it wait for the lock, then commits [first]. */
private fun <A, B> overlapping(
    tenant: UUID,
    first: (JooqOverrideStore) -> A,
    second: (JooqOverrideStore) -> B,
): Pair<A, B> {
    val pool = Executors.newSingleThreadExecutor()
    try {
        OpenTransaction(tenant).use { a ->
            OpenTransaction(tenant).use { b ->
                try {
                    val firstResult = first(a.store)
                    val job = pool.submit<B> { second(b.store).also { b.commit() } }
                    awaitBlocked(job, b.pid)
                    a.commit()
                    return firstResult to job.get(60, TimeUnit.SECONDS)
                } finally {
                    // b may still wait for a, and closing b waits for its statement: end a first (no-op once committed)
                    a.rollback()
                }
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

/** A row released before the release sequence existed: only a superuser can write it, past the store. */
private fun unnumberedRelease(
    tenant: UUID,
    doc: UUID,
    code: String,
) = PayloadDb.superuser().use { c ->
    c
        .prepareStatement(
            "insert into payload.validation_override " +
                "(tenant_id, doc_uuid, version, code, reason, status, requested_by, " +
                "released_by, released_at) " +
                "values (?, ?, 1, ?, 'r', 'RELEASED', 'A1', 'A1', now())",
        ).use { p ->
            p.setObject(1, tenant)
            p.setObject(2, doc)
            p.setString(3, code)
            p.executeUpdate()
        }
}

/** The SQL state with which the database refuses [releaseSequence] on a new row of [status], or null. */
private fun refusal(
    tenant: UUID,
    doc: UUID,
    code: String,
    status: String,
    releaseSequence: Int?,
): String? =
    PayloadDb.superuser().use { c ->
        val released = if (status == "RELEASED") "'A1', now()" else "null, null"
        c
            .prepareStatement(
                "insert into payload.validation_override (tenant_id, doc_uuid, version, code, reason, " +
                    "status, requested_by, released_by, released_at, release_sequence) " +
                    "values (?, ?, 1, ?, 'r', ?, 'A1', $released, ?)",
            ).use { p ->
                p.setObject(1, tenant)
                p.setObject(2, doc)
                p.setString(3, code)
                p.setString(4, status)
                p.setObject(5, releaseSequence, java.sql.Types.INTEGER)
                runCatching { p.executeUpdate() }
                    .exceptionOrNull()
                    ?.let { (it as java.sql.SQLException).sqlState }
            }
    }
