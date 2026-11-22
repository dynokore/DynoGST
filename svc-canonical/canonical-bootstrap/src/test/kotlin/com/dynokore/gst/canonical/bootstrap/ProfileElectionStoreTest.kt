package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.persistence.JooqProfileElections
import com.dynokore.gst.canonical.adapters.persistence.ProfileElectionEntry
import com.dynokore.gst.canonical.adapters.persistence.ProfileElectionSet
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PROFILE_ELECTION
import com.dynokore.gst.canonical.domain.dating.DateBasis
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/** The profile election table against a real database, as the `canonical_runtime` login (INV-Q-21422 A). */
class ProfileElectionStoreTest {
    private val actor = SignedBindings.ACTOR
    private val jan = LocalDate.of(2026, 1, 1)
    private val jul = LocalDate.of(2026, 7, 1)
    private val sep = LocalDate.of(2026, 9, 1)

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val store = JooqProfileElections(dsl)
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        CanonicalContext.online(dir).run { block(Boundary(it)) }
    }

    private fun Boundary.applyAs(
        tenant: UUID,
        version: Long,
        vararg entries: ProfileElectionEntry,
    ): Boolean =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction { store.apply(ProfileElectionSet(tenant, "E1", version, entries.toList())) }
        }

    private fun Boundary.basisAs(
        session: UUID,
        tenant: UUID,
        on: LocalDate,
    ): DateBasis? = scope.bind(TenantId(session), actor) { runner.inTransaction { store.electionOf(tenant, "E1", on) } }

    private fun Boundary.schemesAs(
        tenant: UUID,
        on: LocalDate,
    ): List<String> =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction { store.electedSchemesOf(tenant, "E1", on) }
        }

    private fun basis(
        value: String,
        on: LocalDate,
    ) = ProfileElectionEntry("purchaseDateBasis", value, on)

    private fun scheme(
        key: String,
        value: String,
        on: LocalDate,
    ) = ProfileElectionEntry("gstScheme.$key", value, on)

    @Test
    fun `INV-AC-21463 version guard per row, no delete, basis in force and elected schemes by date`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val a1 = b.applyAs(t1, 5, basis("INVOICE_DATE", jan), basis("POSTING_DATE", jul), scheme("ARCS", "ON", jan))
        a1 shouldBe true
        b.applyAs(t1, 4, basis("INVOICE_DATE", jul)) shouldBe false
        // A2 changed nothing: the July row still holds POSTING_DATE
        b.basisAs(t1, t1, jul) shouldBe DateBasis.POSTING_DATE
        b.applyAs(t1, 6, scheme("ARCS", "OFF", sep)) shouldBe true
        b.applyAs(t1, 5, basis("INVOICE_DATE", jan)) shouldBe false

        b.basisAs(t1, t1, LocalDate.of(2025, 12, 31)) shouldBe null
        b.basisAs(t1, t1, LocalDate.of(2026, 6, 30)) shouldBe DateBasis.INVOICE_DATE
        b.basisAs(t1, t1, jul) shouldBe DateBasis.POSTING_DATE
        b.schemesAs(t1, LocalDate.of(2026, 8, 31)) shouldBe listOf("ARCS")
        b.schemesAs(t1, sep) shouldBe emptyList()
        b.basisAs(t2, t2, jul) shouldBe null
        // a session bound to T2 reads nothing of T1 even when it asks for T1
        b.basisAs(t2, t1, jul) shouldBe null
    }

    @Test
    fun `INV-AC-21463 a higher version replaces the row, schemes are listed ascending, OFF is not elected`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 1, scheme("ZED", "ON", jan), scheme("ARCS", "ON", jan), scheme("MID", "OFF", jan)) shouldBe true
        b.schemesAs(t1, jan) shouldBe listOf("ARCS", "ZED")
        b.applyAs(t1, 2, basis("INVOICE_DATE", jan)) shouldBe true
        b.applyAs(t1, 3, basis("POSTING_DATE", jan)) shouldBe true
        b.basisAs(t1, t1, jan) shouldBe DateBasis.POSTING_DATE
        b.schemesAs(t1, jan) shouldBe listOf("ARCS", "ZED")
    }

    @Test
    fun `INV-AC-21463 a basis other than the two values is refused and a delete is not granted`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 1, basis("INVOICE_DATE", jan)) shouldBe true

        fun sqlState(sql: String): String? =
            CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
                connection.autoCommit = false
                SignedBindings.bind(connection, t1)
                try {
                    connection.createStatement().use { it.executeUpdate(sql) }
                    null
                } catch (refused: java.sql.SQLException) {
                    refused.sqlState
                } finally {
                    connection.rollback()
                }
            }
        sqlState(
            "insert into canonical.profile_election " +
                "values ('$t1', 'E1', 'purchaseDateBasis', date '2026-02-01', 'BAD', 1)",
        ) shouldBe "23514"
        // the tenant policy refuses a row of another tenant bound session (row level security, not the query)
        sqlState(
            "insert into canonical.profile_election " +
                "values ('${UUID.randomUUID()}', 'E1', 'purchaseDateBasis', date '2026-02-01', 'INVOICE_DATE', 1)",
        ) shouldBe "42501"
        sqlState("delete from canonical.profile_election where entity_ref = 'E1'") shouldBe "42501"
        b.basisAs(t1, t1, jan) shouldBe DateBasis.INVOICE_DATE
    }

    private fun Boundary.electedAs(
        session: UUID,
        tenant: UUID,
        entity: String,
        attribute: String,
        on: LocalDate,
    ): Boolean =
        scope.bind(TenantId(session), actor) { runner.inTransaction { store.isElected(tenant, entity, attribute, on) } }

    private fun Boundary.sqlStateOf(
        tenant: UUID,
        entry: ProfileElectionEntry,
    ): String? =
        runCatching { applyAs(tenant, 1, entry) }.exceptionOrNull()?.let { failure ->
            generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                ?: "not a SQL violation: $failure"
        }

    private fun mode(
        value: String,
        on: LocalDate,
    ) = ProfileElectionEntry("apportionmentMode", value, on)

    private fun source(
        value: String,
        on: LocalDate,
    ) = ProfileElectionEntry("exchangeRateSource", value, on)

    @Test
    fun `INV-AC-26604 R1 R2 R4 isElected is true on the effective date and false the day before`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        val before = jul.minusDays(1)
        b.applyAs(t1, 1, mode("CLAIMABLE_PORTION", jul), source("MAS_DAILY", jul)) shouldBe true
        b.applyAs(t1, 1, mode("ENTIRE_INVOICE", sep)) shouldBe true
        b.electedAs(t1, t1, "E1", "apportionmentMode", jul) shouldBe true
        b.electedAs(t1, t1, "E1", "apportionmentMode", before) shouldBe false
        b.electedAs(t1, t1, "E1", "apportionmentMode", sep) shouldBe true
        b.electedAs(t1, t1, "E1", "apportionmentMode", sep.minusDays(1)) shouldBe true
        b.electedAs(t1, t1, "E1", "exchangeRateSource", jul) shouldBe true
        b.electedAs(t1, t1, "E1", "exchangeRateSource", before) shouldBe false
    }

    @Test
    fun `INV-AC-26604 R2 the ENTIRE_INVOICE value alone is elected on its date and not the day before`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 1, mode("ENTIRE_INVOICE", jul)) shouldBe true
        b.electedAs(t1, t1, "E1", "apportionmentMode", jul) shouldBe true
        b.electedAs(t1, t1, "E1", "apportionmentMode", jul.minusDays(1)) shouldBe false
    }

    @Test
    fun `INV-AC-26604 R5 a higher version replaces the value and a lower one is ignored for both attributes`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.applyAs(t1, 5, mode("CLAIMABLE_PORTION", jan), source("MAS_DAILY", jan)) shouldBe true
        b.applyAs(t1, 4, mode("ENTIRE_INVOICE", jan), source("OTHER_SRC", jan)) shouldBe false
        b.applyAs(t1, 5, mode("ENTIRE_INVOICE", jan), source("OTHER_SRC", jan)) shouldBe false
        b.valuesOf(t1) shouldBe listOf("apportionmentMode=CLAIMABLE_PORTION#5", "exchangeRateSource=MAS_DAILY#5")
        b.applyAs(t1, 6, mode("ENTIRE_INVOICE", jan), source("OTHER_SRC", jan)) shouldBe true
        b.valuesOf(t1) shouldBe listOf("apportionmentMode=ENTIRE_INVOICE#6", "exchangeRateSource=OTHER_SRC#6")
    }

    private fun Boundary.valuesOf(tenant: UUID): List<String> =
        scope.bind(TenantId(tenant), actor) {
            runner.inTransaction {
                dsl
                    .selectFrom(PROFILE_ELECTION)
                    .orderBy(PROFILE_ELECTION.ATTRIBUTE)
                    .fetch()
                    .map { "${it.attribute}=${it.value}#${it.version}" }
            }
        }

    @Test
    fun `INV-AC-26604 R6 an election is not elected for another tenant, entity or attribute`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val a = UUID.randomUUID()
        val other = UUID.randomUUID()
        b.applyAs(a, 1, mode("CLAIMABLE_PORTION", jan)) shouldBe true
        // the other tenant holds the same entity and attribute, so only the tenant predicate keeps A's row apart
        b.applyAs(other, 1, mode("CLAIMABLE_PORTION", jan)) shouldBe true
        b.electedAs(other, other, "E1", "apportionmentMode", jul) shouldBe true
        b.electedAs(a, a, "E1", "apportionmentMode", jul) shouldBe true
        b.electedAs(a, a, "E2", "apportionmentMode", jul) shouldBe false
        b.electedAs(a, a, "E1", "exchangeRateSource", jul) shouldBe false
        // a session bound to the other tenant asking for tenant A reads nothing
        b.electedAs(other, a, "E1", "apportionmentMode", jul) shouldBe false
    }

    @Test
    fun `INV-AC-26604 M1 M2 M3 M4 M5 the database refuses what the checks do not admit with 23514`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.sqlStateOf(t1, ProfileElectionEntry("issuanceMode", "RELAY", jan)) shouldBe "23514"
        b.sqlStateOf(t1, ProfileElectionEntry("gstScheme.abc", "ON", jan)) shouldBe "23514"
        b.sqlStateOf(t1, ProfileElectionEntry("gstScheme.", "ON", jan)) shouldBe "23514"
        b.sqlStateOf(t1, mode("BOTH", jan)) shouldBe "23514"
        b.sqlStateOf(t1, basis("BOTH", jan)) shouldBe "23514"
        b.sqlStateOf(t1, source("X".repeat(41), jan)) shouldBe "23514"
        b.sqlStateOf(t1, source("", jan)) shouldBe "23514"
    }

    @Test
    fun `INV-AC-26604 M6 the two new attributes and a scheme code with a digit are stored`(
        @TempDir dir: Path,
    ) = run(dir) { b ->
        val t1 = UUID.randomUUID()
        b.sqlStateOf(t1, mode("ENTIRE_INVOICE", jan)) shouldBe null
        b.sqlStateOf(t1, source("MAS_DAILY", jan)) shouldBe null
        b.sqlStateOf(t1, scheme("GST9", "ON", jan)) shouldBe null
    }
}
