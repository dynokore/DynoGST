package com.dynokore.gst.consoleBff.application.portfolio

import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.gst.consoleBff.domain.portfolio.Cell
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.gst.consoleBff.domain.portfolio.PortfolioRow
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class GetPortfolioTest {
    /** Fake of the tenancy edge: the use case must never call it. */
    private class TenancyEdge {
        var calls = 0

        @Suppress("unused")
        fun listPeriodRecords(entityRef: String): Nothing {
            calls++
            error("no downstream call: $entityRef")
        }
    }

    private class Fixture(
        val scopeOf: Set<String>,
        val facts: List<StoredFact>,
    ) : EntityScope,
        PortfolioStore {
        val tenancy = TenancyEdge()

        override fun entitiesOf(
            tenantId: String,
            principalId: String,
        ) = scopeOf

        override fun putPeriods(
            tenantId: String,
            rows: List<StoredPeriod>,
        ) = error("read use case never writes")

        override fun putReadiness(
            tenantId: String,
            row: StoredReadiness,
            observedAt: Instant,
        ): Boolean = error("read use case never writes")

        override fun facts(
            tenantId: String,
            entityRefs: Set<String>,
        ) = facts.filter { it.period.entityRef in entityRefs }
    }

    private fun d(s: String) = LocalDate.parse(s)

    private fun period(
        entity: String,
        ref: String,
        state: PeriodState,
        due: String,
        filed: String? = null,
    ) = StoredFact(StoredPeriod(entity, ref, state, d(due), filed?.let { d(it) }, false), null)

    private fun StoredFact.with(r: StoredReadiness) = copy(readiness = r)

    private fun ready(entity: String) = StoredReadiness(entity, "2026Q4", Verdict.READY, null)

    private fun blocked(
        entity: String,
        n: Int,
    ) = StoredReadiness(entity, "2026Q4", Verdict.BLOCKED, n)

    /** E1 late and filed, E2 due and ready, E3 due and blocked by 2, E9 outside the scope. */
    private fun fixture(scope: Set<String> = setOf("E1", "E2", "E3")) = Fixture(scope, fixtureFacts())

    private fun fixtureFacts() =
        listOf(
            period("E1", "2026Q3", PeriodState.LATE, "2026-10-31", "2026-11-05"),
            period("E1", "2026Q4", PeriodState.DUE, "2027-03-31"),
            period("E2", "2026Q4", PeriodState.DUE, "2027-01-31").with(ready("E2")),
            period("E3", "2026Q4", PeriodState.DUE, "2027-02-28").with(blocked("E3", 2)),
            period("E9", "2026Q4", PeriodState.DUE, "2026-12-01").with(ready("E9")),
        )

    private fun use(
        f: Fixture,
        max: Int = 500,
    ) = GetPortfolio(f, f, max)

    private fun q(
        limit: Int = 100,
        cursor: String? = null,
    ) = PortfolioQuery("T1", "P1", limit, cursor)

    private fun listed(r: PortfolioResult) = r as PortfolioResult.Listed

    private fun dueNoVerdict(verdict: Verdict?) = Cell(PeriodState.DUE, null, false, verdict, null)

    @Test
    fun `INV-AC-26280 late with filing date, ready and blocked with count 2, in the order of nearest due date`() {
        val r = listed(use(fixture()).list(q()))
        r.periodRefs shouldBe listOf("2026Q4")
        r.rows shouldBe
            listOf(
                PortfolioRow("E2", mapOf("2026Q4" to Cell(PeriodState.DUE, null, false, Verdict.READY, null))),
                PortfolioRow("E3", mapOf("2026Q4" to Cell(PeriodState.DUE, null, false, Verdict.BLOCKED, 2))),
                PortfolioRow(
                    "E1",
                    mapOf("2026Q4" to dueNoVerdict(Verdict.UNABLE_TO_DETERMINE)),
                ),
            )
        r.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-26280 the late cell carries the filing date`() {
        val f =
            Fixture(
                setOf("E1", "E2"),
                listOf(
                    period("E1", "2026Q3", PeriodState.LATE, "2026-10-31", "2026-11-05"),
                    period("E2", "2026Q3", PeriodState.DUE, "2026-10-31").with(ready("E2")),
                ),
            )
        listed(use(f).list(q())).rows.single { it.entityRef == "E1" }.cells["2026Q3"] shouldBe
            Cell(PeriodState.LATE, d("2026-11-05"), false, null, null)
    }

    @Test
    fun `INV-AC-26280 a due period with no stored readiness is unable to determine`() {
        val f = Fixture(setOf("E5"), listOf(period("E5", "2026Q4", PeriodState.DUE, "2027-01-31")))
        listed(use(f).list(q())).rows.single().cells["2026Q4"] shouldBe dueNoVerdict(Verdict.UNABLE_TO_DETERMINE)
    }

    @Test
    fun `INV-AC-26280 look-alike - a filed period with no stored readiness gives no verdict`() {
        val f =
            Fixture(
                setOf("E5", "E6"),
                listOf(
                    period("E5", "2026Q4", PeriodState.FILED, "2027-01-31", "2027-01-20"),
                    period("E6", "2026Q4", PeriodState.DUE, "2027-01-31"),
                ),
            )
        listed(use(f).list(q())).rows.single { it.entityRef == "E5" }.cells["2026Q4"] shouldBe
            Cell(PeriodState.FILED, d("2027-01-20"), false, null, null)
    }

    @Test
    fun `INV-AC-26280 an entity outside the scope is absent`() {
        listed(use(fixture()).list(q())).rows.map { it.entityRef } shouldBe listOf("E2", "E3", "E1")
    }

    @Test
    fun `INV-AC-26280 an empty scope gives an empty list`() {
        val r = listed(use(fixture(emptySet())).list(q()))
        r.rows shouldBe emptyList()
        r.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-26280 a limit of 0 and a limit above the maximum are bad input`() {
        val f = fixture()
        use(f, 500).list(q(limit = 0)) shouldBe PortfolioResult.BadLimit
        use(f, 500).list(q(limit = 501)) shouldBe PortfolioResult.BadLimit
        use(f, 7).list(q(limit = 8)) shouldBe PortfolioResult.BadLimit
        listed(use(f, 7).list(q(limit = 7))).rows.size shouldBe 3
    }

    @Test
    fun `INV-AC-26280 a cursor that does not decode is bad input`() {
        use(fixture()).list(q(cursor = "not-a-cursor!")) shouldBe PortfolioResult.BadCursor
        use(fixture()).list(q(cursor = "")) shouldBe PortfolioResult.BadCursor
    }

    @Test
    fun `INV-AC-26280 keyset paging returns each row once in order`() {
        val f = fixture()
        val first = listed(use(f).list(q(limit = 2)))
        first.rows.map { it.entityRef } shouldBe listOf("E2", "E3")
        val second = listed(use(f).list(q(limit = 2, cursor = first.nextCursor)))
        second.rows.map { it.entityRef } shouldBe listOf("E1")
        second.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-26280 the fake tenancy edge records zero calls`() {
        val f = fixture()
        use(f).list(q())
        use(f).list(q(limit = 1))
        f.tenancy.calls shouldBe 0
    }

    @Test
    fun `INV-AC-26280 no member of the result is named score`() {
        val names =
            listOf(PortfolioResult.Listed::class.java, PortfolioRow::class.java, Cell::class.java).flatMap { c ->
                c.declaredFields.map { it.name.lowercase() } + c.declaredMethods.map { it.name.lowercase() }
            }
        names.filter { "score" in it } shouldBe emptyList()
    }
}
