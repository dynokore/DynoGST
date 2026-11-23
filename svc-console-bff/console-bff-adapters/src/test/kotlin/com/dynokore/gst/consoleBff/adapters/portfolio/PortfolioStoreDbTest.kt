package com.dynokore.gst.consoleBff.adapters.portfolio

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioStore
import com.dynokore.gst.consoleBff.application.portfolio.StoredFact
import com.dynokore.gst.consoleBff.application.portfolio.StoredPeriod
import com.dynokore.gst.consoleBff.application.portfolio.StoredReadiness
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The portfolio read model on a live PostgreSQL as the runtime role under a signed binding (INV-AC-26279): the rows
 * read back equal the fixture written, the newer readiness observation wins, tenants are isolated, and both tables
 * carry the tenant row level security block, the tenant column and the least-privilege grants.
 */
class PortfolioStoreDbTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611211000__console_bff_portfolio.sql")).readText()

    private val t0 = Instant.parse("2026-11-01T10:00:00Z")
    private val t1 = Instant.parse("2026-11-02T10:00:00Z")

    private val p1 = StoredPeriod("E1", "P1", PeriodState.DUE, LocalDate.parse("2026-12-01"), null, true)
    private val p2 =
        StoredPeriod("E1", "P2", PeriodState.FILED, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-08-20"), false)
    private val p3 = StoredPeriod("E1", "P3", PeriodState.LATE, LocalDate.parse("2026-06-01"), null, false)
    private val blocked2 = StoredReadiness("E1", "P1", Verdict.BLOCKED, 2)
    private val ready = StoredReadiness("E1", "P1", Verdict.READY, null)
    private val unable = StoredReadiness("E1", "P3", Verdict.UNABLE_TO_DETERMINE, null)

    private fun <T> asTenant(
        tenant: UUID?,
        block: (PortfolioStore) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            if (tenant != null) SignedBindings.bind(c, tenant)
            val result = block(JooqPortfolioStore(DSL.using(c, SQLDialect.POSTGRES)))
            c.commit()
            result
        }

    private fun <T> catalog(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    private fun factsOf(
        tenant: UUID,
        vararg entities: String,
    ): List<StoredFact> = asTenant(tenant) { it.facts(tenant.toString(), entities.toSet()) }

    @Test
    fun `INV-AC-26279 the written facts are read back equal and an entity not written has no row`() {
        val t = UUID.randomUUID()
        asTenant(t) { s ->
            s.putPeriods(t.toString(), listOf(p1, p2, p3))
            s.putReadiness(t.toString(), blocked2, t0) shouldBe true
            s.putReadiness(t.toString(), unable, t0) shouldBe true
        }
        factsOf(t, "E1") shouldContainExactlyInAnyOrder
            listOf(StoredFact(p1, blocked2), StoredFact(p2, null), StoredFact(p3, unable))
        factsOf(t, "E1").first { it.period.periodRef == "P1" }.readiness?.blockedCount shouldBe 2
        asTenant(t) { it.putReadiness(t.toString(), ready, t1) } shouldBe true
        factsOf(t, "E1").first { it.period.periodRef == "P1" }.readiness shouldBe ready
        factsOf(t, "E1").first { it.period.periodRef == "P1" }.readiness?.blockedCount shouldBe null
        asTenant(t) { it.putPeriods(t.toString(), listOf(p1.copy(state = PeriodState.LATE, extensionGranted = false))) }
        factsOf(t, "E1").first { it.period.periodRef == "P1" }.period shouldBe
            p1.copy(state = PeriodState.LATE, extensionGranted = false)
        factsOf(t, "E2") shouldBe emptyList()
        asTenant(t) { it.facts(t.toString(), emptySet()) } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26279 a readiness fact with an older observation time leaves the newer one`() {
        val t = UUID.randomUUID()
        asTenant(t) { it.putPeriods(t.toString(), listOf(p1)) }
        asTenant(t) { it.putReadiness(t.toString(), blocked2, t1) } shouldBe true
        asTenant(t) { it.putReadiness(t.toString(), ready, t0) } shouldBe false
        asTenant(t) { it.putReadiness(t.toString(), ready, t1) } shouldBe false
        factsOf(t, "E1") shouldBe listOf(StoredFact(p1, blocked2))
        val u = UUID.randomUUID()
        asTenant(u) { it.putPeriods(u.toString(), listOf(p1)) }
        asTenant(u) { it.putReadiness(u.toString(), ready, t0) } shouldBe true
        asTenant(u) { it.putReadiness(u.toString(), blocked2, t1) } shouldBe true
        factsOf(u, "E1") shouldBe listOf(StoredFact(p1, blocked2))
        factsOf(t, "E1") shouldBe listOf(StoredFact(p1, blocked2))
    }

    @Test
    fun `INV-AC-26279 the second tenant reads no row of the first and the same entity keeps its own rows`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        asTenant(a) {
            it.putPeriods(a.toString(), listOf(p1))
            it.putReadiness(a.toString(), blocked2, t0)
        }
        factsOf(b, "E1") shouldBe emptyList()
        asTenant(b) { it.facts(a.toString(), setOf("E1")) } shouldBe emptyList()
        asTenant(null) { it.facts(a.toString(), setOf("E1")) } shouldBe emptyList()
        asTenant(b) {
            it.putPeriods(b.toString(), listOf(p1.copy(state = PeriodState.OPEN, extensionGranted = false)))
            it.putReadiness(b.toString(), ready, t0)
        }
        factsOf(a, "E1") shouldBe listOf(StoredFact(p1, blocked2))
        factsOf(b, "E1") shouldBe
            listOf(StoredFact(p1.copy(state = PeriodState.OPEN, extensionGranted = false), ready))
    }

    @Test
    fun `INV-AC-26279 both tables carry the tenant column, the RLS block, the grants and the constraints`() {
        for (table in listOf("portfolio_period", "portfolio_readiness")) {
            val begin = "-- BEGIN TenantRlsMigration.ddl(\"console_bff\", \"$table\")"
            val section = migration.substringAfter(begin, "").substringBefore("-- END TenantRlsMigration.ddl", "")
            section.isNotBlank() shouldBe true
            section.trim() shouldBe TenantRlsMigration.ddl("console_bff", table).trim()
            catalog(
                "select data_type, is_nullable from information_schema.columns " +
                    "where table_schema = 'console_bff' and table_name = '$table' and column_name = 'tenant_id'",
            ) { it.getString(1) to it.getString(2) } shouldBe listOf("uuid" to "NO")
            catalog(
                "select relrowsecurity, relforcerowsecurity from pg_class where oid = 'console_bff.$table'::regclass",
            ) { it.getBoolean(1) to it.getBoolean(2) } shouldBe listOf(true to true)
            catalog(
                "select policyname from pg_policies where schemaname = 'console_bff' and tablename = '$table'",
            ) { it.getString(1) } shouldBe listOf("tenant_isolation")
            catalog(
                "select has_table_privilege('console_bff_runtime', 'console_bff.$table', 'SELECT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.$table', 'INSERT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.$table', 'UPDATE'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.$table', 'DELETE')",
            ) { listOf(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4)) } shouldBe
                listOf(listOf(true, true, true, false))
        }
        val t = UUID.randomUUID()

        fun insert(
            verdict: String,
            count: String,
            state: String = "DUE",
        ) = ConsoleDb.superuser().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "insert into console_bff.portfolio_readiness values ('$t', 'E1', 'P9', '$verdict', $count, now())",
                )
                it.executeUpdate(
                    "insert into console_bff.portfolio_period " +
                        "values ('$t', 'E1', 'P9', '$state', '2026-12-01', null, false)",
                )
            }
        }
        shouldThrow<SQLException> { insert("READY", "3") }
        shouldThrow<SQLException> { insert("BLOCKED", "null") }
        shouldThrow<SQLException> { insert("BLOCKED", "-1") }
        shouldThrow<SQLException> { insert("MAYBE", "null") }
        shouldThrow<SQLException> { insert("READY", "null", state = "UNKNOWN") }
    }
}
