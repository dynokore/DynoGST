package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.ReadinessReplaySource
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessReplayReader
import com.dynokore.gst.submission.application.readiness.ReadinessChangedFacts
import com.dynokore.gst.submission.application.readiness.ReadinessChangedPublication
import com.dynokore.gst.submission.application.readiness.ReadinessSnapshot
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** INV-AC-29140: the replay source of ReadinessChanged over the stored readiness signals (INV-T-29143). */
class ReadinessReplaySourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val source =
        ReadinessReplaySource(JooqReadinessReplayReader(DSL.using(connection, SQLDialect.POSTGRES)), jacksonObjectMapper())
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val snapshots =
        mapOf(
            "E1/P1" to ReadinessSnapshot(CheckVerdict.NOT_READY, 2, mapOf(LifecycleState.BLOCKED to 1, LifecycleState.QUEUED to 1)),
            "E1/P2" to ReadinessSnapshot(CheckVerdict.READY, 0, emptyMap<LifecycleState, Int>()),
            "E2/P1" to ReadinessSnapshot(CheckVerdict.NOT_READY, 1, mapOf(LifecycleState.HELD to 1)),
            "E10/P1" to ReadinessSnapshot(CheckVerdict.READY, 0, mapOf(LifecycleState.SENT to 3)),
        )

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { it.execute("delete from submission.readiness_signal where tenant_id in ('$a', '$b')") }
        }
    }

    private fun seed() {
        SubmissionDb.superuser().use { c ->
            // E1/P1 signalled 08:00, E1/P2 09:00, E2/P1 10:00, E10/P1 11:00; tenant b holds E1/P1 at 09:30
            val hours = mapOf("E1/P1" to 8, "E1/P2" to 9, "E2/P1" to 10, "E10/P1" to 11)
            snapshots.forEach { (key, s) -> insert(c, a, key, s, hours.getValue(key), 0) }
            insert(c, b, "E1/P1", snapshots.getValue("E1/P1"), 9, 30)
        }
    }

    private fun insert(c: Connection, tenant: UUID, key: String, s: ReadinessSnapshot, hour: Int, minute: Int) {
        val (entity, period) = key.split('/')
        val counts = s.countsByState.entries.joinToString(",", "{", "}") { "\"${it.key.name}\":${it.value}" }
        c.createStatement().use {
            it.execute(
                "insert into submission.readiness_signal values ('$tenant', '$entity', '$period', '${s.verdict.name}', " +
                    "${s.blockedCount}, '$counts'::jsonb, timestamptz '2026-11-21 %02d:%02d:00+00')".format(hour, minute),
            )
        }
    }

    private fun <T> inTx(tenant: UUID, block: () -> T): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block()
        } finally {
            connection.commit()
        }
    }

    private fun all(tenant: UUID, scope: ReplayScope?, limit: Int): List<ReplayedState> =
        inTx(tenant) {
            val out = mutableListOf<ReplayedState>()
            var cursor: String? = null
            do {
                val page = source.page(scope, cursor, limit)
                out += page.items
                cursor = page.next
            } while (cursor != null)
            out
        }

    private fun expected(key: String): ReplayedState {
        val (entity, period) = key.split('/')
        return ReplayedState(
            ReadinessChangedPublication.EVENT_TYPE,
            ReadinessChangedPublication.data(ReadinessChangedFacts(entity, period, snapshots.getValue(key), "live")),
            EntityRef(entity),
            key,
            null,
        )
    }

    private fun at(hour: Int, minute: Int = 0) = Instant.parse("2026-11-21T%02d:%02d:00Z".format(hour, minute))

    @Test
    fun `INV-AC-29140 the full scope returns each stored signal once with the live data, page by page`() {
        seed()
        val keys = listOf("E1/P1", "E1/P2", "E10/P1", "E2/P1") // text order of the key
        for (limit in listOf(1, 2, 3, 10)) {
            all(a, null, limit).map { it.aggregateKey } shouldBe keys
        }
        all(a, null, 2) shouldBe keys.map(::expected)
        source.eventType shouldBe ReadinessChangedPublication.EVENT_TYPE
    }

    @Test
    fun `INV-AC-29140 a tenant page holds no signal of another tenant`() {
        seed()
        all(b, ReplayScope.Tenant(TenantId(b)), 5) shouldBe listOf(expected("E1/P1"))
        all(a, ReplayScope.Tenant(TenantId(a)), 5).size shouldBe 4
    }

    @Test
    fun `INV-AC-29140 the entity scope returns the signals of that entity only`() {
        seed()
        all(a, ReplayScope.Entity(TenantId(a), EntityRef("E1")), 1).map { it.aggregateKey } shouldBe listOf("E1/P1", "E1/P2")
    }

    @Test
    fun `INV-AC-29140 the period scope is half open on signalled_at`() {
        seed()
        all(a, ReplayScope.Period(at(9), at(11), null), 5).map { it.aggregateKey } shouldBe listOf("E1/P2", "E2/P1")
        all(a, ReplayScope.Period(at(9), at(11), TenantId(a)), 5).map { it.aggregateKey } shouldBe listOf("E1/P2", "E2/P1")
    }

    @Test
    fun `INV-AC-29140 the tenant list holds each tenant with a signal once`() {
        seed()
        val tenants = source.tenants()
        tenants.map { it.value }.toSet().size shouldBe tenants.size
        tenants.map { it.value } shouldContainAll listOf(a, b)
    }

    @Test
    fun `INV-AC-29140 a concurrent change of a returned row commits only after the page transaction ends`() {
        seed()
        SignedBindings.bind(connection, a)
        source.page(null, null, 10).items.size shouldBe 4
        val other = SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val change =
                pool.submit {
                    SignedBindings.bind(other, a)
                    other.createStatement().use {
                        it.executeUpdate(
                            "update submission.readiness_signal set blocked_count = 9, verdict = 'NOT_READY' " +
                                "where entity_ref = 'E1' and period_ref = 'P2'",
                        )
                    }
                    other.commit()
                }
            var committedEarly = true
            try {
                change.get(1500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                committedEarly = false
            }
            committedEarly shouldBe false
            connection.commit()
            change.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            other.close()
        }
    }

    @Test
    fun `INV-AC-29140 nothing is written to readiness_signal`() {
        seed()
        val before = count()
        all(a, null, 2)
        count() shouldBe before
    }

    @Test
    fun `INV-AC-29140 the tenant function is executable by the runtime role only and returns tenant ids`() {
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select has_function_privilege('submission_runtime', p.oid, 'execute') || '|' || " +
                        "has_function_privilege('public', p.oid, 'execute') || '|' || p.prosecdef || '|' || " +
                        "pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ',') || '|' || " +
                        "pg_get_function_result(p.oid) from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'submission' and p.proname = 'readiness_replay_tenants'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe
                        "true|false|true|submission_sys|search_path=pg_catalog, submission, pg_temp|TABLE(replay_tenant uuid)"
                }
            }
        }
    }

    private fun count(): String =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select count(*) || '|' || coalesce(sum(blocked_count), 0) from submission.readiness_signal where tenant_id in ('$a', '$b')").use {
                    it.next()
                    it.getString(1)
                }
            }
        }
}
