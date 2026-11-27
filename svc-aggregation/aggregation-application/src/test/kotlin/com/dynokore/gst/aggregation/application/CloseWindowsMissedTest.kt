package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-27726 with fake ports: the due parts a closing run could not close (INV-REQ-413). */
class CloseWindowsMissedTest {
    private fun d(
        m: Int,
        day: Int,
    ) = LocalDate.of(2027, m, day)

    /** The business unit `bu-bad` has no short code until [repaired], so no aggregate of it can be made. */
    private class Codes : ShortCodes {
        var repaired = false

        override fun entityCode(entityRef: String) = "E"

        override fun businessUnitCode(businessUnitRef: String) =
            if (businessUnitRef == "bu-bad" && !repaired) null else "B"
    }

    private class AggregateRepo : AggregateRepository {
        val rows = mutableListOf<StoredAggregate>()
        private var sequence = 0L

        override fun findByIntakeKey(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
        ) = rows.firstOrNull {
            it.aggregate.entityRef == entityRef &&
                it.aggregate.sourceKey == sourceKey &&
                it.aggregate.sourceRevision == sourceRevision
        }

        override fun highestRevision(
            entityRef: String,
            sourceKey: String,
        ) = rows.filter { it.aggregate.sourceKey == sourceKey }.maxOfOrNull { it.aggregate.sourceRevision }

        override fun nextSequence() = ++sequence

        override fun store(aggregate: NewAggregate) = StoredAggregate(aggregate, UUID.randomUUID()).also { rows += it }
    }

    private class Windows(
        private val open: List<OpenWindow>,
    ) : WindowRepository {
        private val closedParts = mutableListOf<Pair<OpenWindow, WindowPart>>()

        override fun place(
            entityRef: String,
            businessUnitRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: Cadence,
        ) = Placement(AggregationWindow.containing(cadence, date), WindowState.OPEN)

        override fun isPartClosed(
            window: OpenWindow,
            part: WindowPart,
        ) = window to part in closedParts

        override fun openWindowsStartingOnOrBefore(date: LocalDate) =
            open.filter { w ->
                val days = closedParts.filter { it.first == w }.sumOf { days(it.second.start, it.second.end) }
                w.window.start <= date && days < days(w.window.start, w.window.end)
            }

        private fun days(
            from: LocalDate,
            to: LocalDate,
        ) = to.toEpochDay() - from.toEpochDay() + 1

        override fun closePart(
            window: OpenWindow,
            part: WindowPart,
            aggregateId: UUID,
        ) {
            closedParts += window to part
        }

        override fun recordLate(
            window: OpenWindow,
            part: WindowPart,
        ) = false
    }

    private val spans =
        listOf(
            PeriodSpan(d(3, 1), d(3, 31), "2027-03"),
            PeriodSpan(d(4, 1), d(4, 30), "2027-04"),
        )

    private fun window(
        entity: String,
        businessUnit: String,
        cadence: Cadence,
        date: LocalDate,
    ) = OpenWindow(entity, businessUnit, AggregateType.PCP, AggregationWindow.containing(cadence, date))

    // the week of 29 March splits at 31 March: A is due on the tick, C (1 to 4 April) is not
    private val withAandC = window("ent-1", "bu-bad", Cadence.WEEKLY, d(3, 30))
    private val withB = window("ent-1", "bu-good", Cadence.WEEKLY, d(3, 22))
    private val w1 = window("ent-unknown", "bu-good", Cadence.DAILY, d(3, 30))

    // starts before the tick, so the run reads it, and ends after it
    private val w2 = window("ent-unknown", "bu-good", Cadence.WEEKLY, d(3, 30))
    private val partA = WindowPart(d(3, 29), d(3, 31), "2027-03")
    private val partB = WindowPart(d(3, 22), d(3, 28), "2027-03")
    private val tick = d(3, 31)
    private val nextTick = d(4, 1)

    private val codes = Codes()
    private val published = mutableListOf<WindowChange>()

    private fun run(open: List<OpenWindow> = listOf(withAandC, withB, w1, w2)) =
        CloseWindows(
            Windows(open),
            { entity, _, _ -> if (entity == "ent-unknown") PeriodSpans.Unknown else PeriodSpans.Known(spans) },
            IntakeAggregate(codes, AggregateRepo()),
        ) { published += it }

    @Test
    fun `INV-AC-27726 only the due unclosable part and the due window with no span are missed`() {
        val result = run().execute(tick)

        result.missed shouldBe listOf(MissedPart(withAandC, partA), MissedPart(w1, null))
        result.closed.map { it.window to it.part } shouldBe listOf(withB to partB)
        result.held shouldBe listOf(withAandC, w1)
    }

    @Test
    fun `INV-AC-27726 the run for the next tick lists the unclosed part and the window with no span again`() {
        val run = run()
        run.execute(tick)

        val second = run.execute(nextTick)

        second.missed shouldBe listOf(MissedPart(withAandC, partA), MissedPart(w1, null))
        second.closed.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-27726 a missed part that closes is listed as closed and no longer as missed`() {
        val run = run()
        run.execute(tick)
        run.execute(nextTick)
        codes.repaired = true

        val third = run.execute(nextTick)

        third.closed.map { it.window to it.part } shouldBe listOf(withAandC to partA)
        third.closed.single().state shouldBe WindowState.CLOSED
        third.missed shouldBe listOf(MissedPart(w1, null))
        published.map { it.part } shouldBe listOf(partB, partA)
    }

    @Test
    fun `INV-AC-27726 every due part of a window is listed and the window is held once`() {
        val result = run(listOf(withAandC)).execute(d(4, 4))

        result.missed shouldBe
            listOf(
                MissedPart(withAandC, partA),
                MissedPart(withAandC, WindowPart(d(4, 1), d(4, 4), "2027-04")),
            )
        result.held shouldBe listOf(withAandC)
    }

    @Test
    fun `INV-AC-27726 a due window with a date no span covers is missed with no part`() {
        val uncovered = window("ent-1", "bu-good", Cadence.WEEKLY, d(4, 28))

        run(listOf(uncovered)).execute(d(5, 1)).missed.shouldBeEmpty()
        run(listOf(uncovered)).execute(d(5, 2)).missed shouldBe listOf(MissedPart(uncovered, null))
    }

    @Test
    fun `INV-AC-27726 the persisted window states stay exactly OPEN, CLOSED and LATE`() {
        WindowState.entries.map { it.name } shouldBe listOf("OPEN", "CLOSED", "LATE")
    }
}
