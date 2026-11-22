package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BackfillEffectTest {
    private fun d(s: String) = BigDecimal(s)

    private val entries =
        listOf(
            MappingEntry(
                "SR",
                Direction.SALE,
                listOf(BoxFeed("1", Basis.TAXABLE_AMOUNT), BoxFeed("6", Basis.TAX_AMOUNT)),
            ),
            MappingEntry("ES", Direction.SALE, listOf(BoxFeed("3", Basis.TAXABLE_AMOUNT))),
        )

    private fun line(
        n: Int,
        code: String,
        amount: String,
        tax: String = "0.00",
    ) = SgdLine(n, code, d(amount), d(tax))

    private fun doc(
        uuid: String,
        acknowledged: Boolean = false,
        allocation: SgdAllocation,
    ) = RunDocument(
        uuid,
        listOf(RunDocumentVersion(1, acknowledged, DocumentFacts(null, null), Direction.SALE, allocation)),
    )

    private fun alloc(vararg lines: SgdLine) = SgdAllocation.Allocated(lines.toList())

    private val d1 =
        doc("D1", allocation = alloc(line(1, "SR", "100.00", "9.00"), line(2, "ES", "50.00"), line(3, "ZZ", "1.00")))
    private val d2 =
        doc("D2", allocation = alloc(line(1, "SR", "-30.00", "-2.70"), line(2, "ES", "-50.00"), line(3, "AA", "1.00")))
    private val d3 = doc("D3", acknowledged = true, allocation = alloc(line(1, "SR", "999.00", "89.00")))
    private val d4 = doc("D4", allocation = SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES))
    private val docs = listOf(d1, d2, d3, d4)
    private val all = listOf("D1", "D2", "D3", "D4", "D5", "D6", "D1")

    @Test
    fun `INV-AC-27690 D1 and D2 give signed per box sums and a zero sum box is absent`() {
        val e = BackfillEffect.project(entries, docs, all)
        e.boxes shouldBe listOf(BoxEffect("1", d("70.00")), BoxEffect("6", d("6.30")))
        e.boxes.forEach { it.projectedChange.scale() shouldBe 2 }
    }

    @Test
    fun `INV-AC-27690 an acknowledged document is excluded ALREADY_ACKNOWLEDGED and adds nothing`() {
        val e = BackfillEffect.project(entries, docs, listOf("D3"))
        e.excluded shouldBe listOf(ExcludedDocument("D3", ExclusionReason.ALREADY_ACKNOWLEDGED))
        e.boxes shouldBe emptyList()
        ExclusionReason.valueOf("ALREADY_ACKNOWLEDGED") shouldBe ExclusionReason.ALREADY_ACKNOWLEDGED
        ExclusionReason.entries.map { it.name } shouldBe listOf("ALREADY_ACKNOWLEDGED", "NOT_FOUND", "NOT_RECONCILABLE")
    }

    @Test
    fun `INV-AC-27690 a not reconcilable chosen version is excluded NOT_RECONCILABLE`() {
        val e = BackfillEffect.project(entries, docs, listOf("D4"))
        e.excluded shouldBe listOf(ExcludedDocument("D4", ExclusionReason.NOT_RECONCILABLE))
        e.boxes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27690 an absent and an unknown docUuid are NOT_FOUND and the excluded list is sorted`() {
        val e = BackfillEffect.project(entries, docs, listOf("D6", "D5", "D3", "D4"))
        e.excluded shouldBe
            listOf(
                ExcludedDocument("D3", ExclusionReason.ALREADY_ACKNOWLEDGED),
                ExcludedDocument("D4", ExclusionReason.NOT_RECONCILABLE),
                ExcludedDocument("D5", ExclusionReason.NOT_FOUND),
                ExcludedDocument("D6", ExclusionReason.NOT_FOUND),
            )
    }

    @Test
    fun `INV-AC-27690 the repeat of a requested document counts once`() {
        val once = BackfillEffect.project(entries, docs, listOf("D1"))
        val twice = BackfillEffect.project(entries, docs, listOf("D1", "D1"))
        twice shouldBe once
        once.boxes shouldBe listOf(BoxEffect("1", d("100.00")), BoxEffect("3", d("50.00")), BoxEffect("6", d("9.00")))
    }

    @Test
    fun `INV-AC-27690 unmapped lines are listed sorted and not counted in a box`() {
        val e = BackfillEffect.project(entries, docs, listOf("D2", "D1"))
        e.unmapped shouldBe listOf(UnmappedLine("D1", 1, 3, "ZZ"), UnmappedLine("D2", 1, 3, "AA"))
        e.boxes.map { it.box } shouldBe listOf("1", "6")
    }

    @Test
    fun `INV-AC-27690 the per box sums equal the sums of the reconciling items of the run`() {
        val outcome =
            ReconciliationRunner
                .run(ReturnFigures(1, mapOf("1" to d("0.00"))), entries, docs)
                .shouldBeInstanceOf<RunOutcome.Completed>()
        val fromRun =
            outcome.reconcilingItems
                .groupBy({ it.box }, { it.amount })
                .mapValues { (_, v) -> v.fold(d("0.00")) { a, b -> a + b } }
                .filterValues { it.signum() != 0 }
        BackfillEffect
            .project(entries, docs, listOf("D1", "D2"))
            .boxes
            .associate { it.box to it.projectedChange } shouldBe fromRun
    }

    @Test
    fun `INV-AC-27690 an empty requested list gives an empty effect`() {
        BackfillEffect.project(entries, docs, emptyList()) shouldBe
            ProjectedEffect(emptyList(), emptyList(), emptyList())
    }
}
