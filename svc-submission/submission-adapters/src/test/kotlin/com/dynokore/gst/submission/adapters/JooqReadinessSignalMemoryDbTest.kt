package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqReadinessSignalMemory
import com.dynokore.gst.submission.application.readiness.ReadinessSnapshot
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** Tests of `submission.readiness_signal` and its compare-and-set memory against the real database (INV-T-26618). */
class JooqReadinessSignalMemoryDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private var bound: UUID = UUID.randomUUID()
    private var now: Instant = Instant.parse("2026-11-21T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val memory =
        JooqReadinessSignalMemory(DSL.using(connection, SQLDialect.POSTGRES), contextOf { bound }, clock)
    private val created = mutableListOf<UUID>()

    private val s1 =
        ReadinessSnapshot(
            CheckVerdict.NOT_READY,
            2,
            mapOf(LifecycleState.BLOCKED to 1, LifecycleState.QUEUED to 1),
        )
    private val s2 = s1.copy(blockedCount = 1)

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use { it.execute("delete from submission.readiness_signal where tenant_id = '$t'") }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun <T> inTx(
        tenant: UUID,
        commit: Boolean = true,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { if (commit) connection.commit() }
        } finally {
            if (!commit) connection.rollback()
        }
    }

    private fun record(
        t: UUID,
        s: ReadinessSnapshot,
        period: String = "P1",
    ) = inTx(t) { memory.record("E1", period, s) }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun ownerRows(sql: String) = SubmissionDb.superuser().use { it.strings(sql) }

    private fun row(
        t: UUID,
        period: String = "P1",
    ) = ownerRows(
        "select verdict || '|' || blocked_count || '|' || counts_by_state::text || '|' || " +
            "to_char(signalled_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') " +
            "from submission.readiness_signal where tenant_id = '$t' and entity_ref = 'E1' and period_ref = '$period'",
    )

    private fun rowOf(
        s: ReadinessSnapshot,
        at: String,
    ): String {
        val counts =
            s.countsByState.entries
                .sortedWith(compareBy({ it.key.name.length }, { it.key.name })) // jsonb text order
                .joinToString(", ", "{", "}") { "\"${it.key.name}\": ${it.value}" }
        return "${s.verdict.name}|${s.blockedCount}|$counts|$at"
    }

    @Test
    fun `INV-AC-26603 the first record answers true, the same again answers false and keeps the time`() {
        val t = tenant()
        record(t, s1) shouldBe true
        row(t) shouldBe listOf(rowOf(s1, "2026-11-21T08:00:00"))
        ownerRows(
            "select data_type from information_schema.columns where table_schema = 'submission' and " +
                "table_name = 'readiness_signal' and column_name = 'counts_by_state'",
        ) shouldBe listOf("jsonb")
        ownerRows("select count(*) from submission.readiness_signal where tenant_id = '$t' and counts_by_state = " +
            "'{\"QUEUED\": 1, \"BLOCKED\": 1}'::jsonb") shouldBe listOf("1")
        now = now.plusSeconds(60)
        record(t, s1) shouldBe false
        row(t) shouldBe listOf(rowOf(s1, "2026-11-21T08:00:00"))
    }

    @Test
    fun `INV-AC-26603 a snapshot differing in blocked count, one state count or verdict answers true`() {
        val t = tenant()
        record(t, s1) shouldBe true
        now = now.plusSeconds(60)
        record(t, s2) shouldBe true
        row(t) shouldBe listOf(rowOf(s2, "2026-11-21T08:01:00"))
        now = now.plusSeconds(60)
        val oneCount = s2.copy(countsByState = mapOf(LifecycleState.BLOCKED to 1, LifecycleState.QUEUED to 2))
        record(t, oneCount) shouldBe true
        row(t) shouldBe listOf(rowOf(oneCount, "2026-11-21T08:02:00"))
        now = now.plusSeconds(60)
        val verdictOnly = oneCount.copy(verdict = CheckVerdict.READY)
        record(t, verdictOnly) shouldBe true
        row(t) shouldBe listOf(rowOf(verdictOnly, "2026-11-21T08:03:00"))
    }

    @Test
    fun `INV-AC-26603 another period and another tenant each answer true and make a separate row`() {
        val t1 = tenant()
        val t2 = tenant()
        record(t1, s1) shouldBe true
        record(t1, s1, "P2") shouldBe true
        record(t2, s1) shouldBe true
        ownerRows("select count(*) from submission.readiness_signal where tenant_id = '$t1'") shouldBe listOf("2")
        ownerRows("select count(*) from submission.readiness_signal where tenant_id = '$t2'") shouldBe listOf("1")
        record(t1, s1) shouldBe false
        record(t2, s1) shouldBe false
    }

    @Test
    fun `INV-AC-26603 a record in a rolled back transaction leaves no row`() {
        val t = tenant()
        inTx(t, commit = false) { memory.record("E1", "P1", s1) } shouldBe true
        ownerRows("select count(*) from submission.readiness_signal where tenant_id = '$t'") shouldBe listOf("0")
    }

    @Test
    fun `INV-AC-26603 the runtime login cannot delete, move a row to another tenant or write a bad verdict or count`() {
        val t1 = tenant()
        val t2 = tenant()
        record(t1, s1)
        record(t2, s1)
        val sql = DSL.using(connection, SQLDialect.POSTGRES)
        fun insert(
            verdict: String,
            count: Int,
        ) = "insert into submission.readiness_signal (tenant_id, entity_ref, period_ref, verdict, blocked_count, " +
            "counts_by_state, signalled_at) values ('$t1', 'E9', 'P1', '$verdict', $count, '{}', now())"
        val refused =
            listOf(
                "delete from submission.readiness_signal",
                "update submission.readiness_signal set tenant_id = '$t2'",
                insert("MAYBE", 0),
                insert("READY", -1),
            )
        for (stmt in refused) {
            assertThrows<Exception>(stmt) { inTx(t1) { sql.execute(stmt) } }
            connection.rollback()
        }
        ownerRows("select count(*) from submission.readiness_signal where tenant_id in ('$t1', '$t2')") shouldBe
            listOf("2")
        inTx(t1) {
            connection.strings("select tenant_id::text from submission.readiness_signal")
        } shouldBe listOf(t1.toString())
        inTx(t1) {
            connection.strings("select tenant_id::text from submission.readiness_signal where tenant_id = '$t2'")
        } shouldBe emptyList()
        ownerRows(
            "select has_table_privilege('submission_runtime', 'submission.readiness_signal', 'DELETE')::text",
        ) shouldBe listOf("false")
    }

    @Test
    fun `INV-AC-26603 two connections recording the same new snapshot at once give exactly one true`() {
        val t = tenant()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val answers =
                (1..2)
                    .map {
                        pool.submit<Boolean> {
                            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { c ->
                                c.autoCommit = false
                                SignedBindings.bind(c, t)
                                barrier.await()
                                JooqReadinessSignalMemory(DSL.using(c, SQLDialect.POSTGRES), contextOf { t }, clock)
                                    .record("E1", "P1", s1)
                                    .also { c.commit() }
                            }
                        }
                    }.map { it.get() }
            answers.count { it } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
        ownerRows("select count(*) from submission.readiness_signal where tenant_id = '$t'") shouldBe listOf("1")
    }
}

private fun contextOf(current: () -> UUID): TenantContext =
    object : TenantContext {
        override fun current(): TenantId = TenantId(current())

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }
