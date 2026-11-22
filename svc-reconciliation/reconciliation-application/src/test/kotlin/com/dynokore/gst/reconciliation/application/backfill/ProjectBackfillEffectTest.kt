package com.dynokore.gst.reconciliation.application.backfill

import com.dynokore.gst.reconciliation.application.run.MappingEntriesSource
import com.dynokore.gst.reconciliation.application.run.RunDocumentsSource
import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.run.BackfillEffect
import com.dynokore.gst.reconciliation.domain.run.BoxEffect
import com.dynokore.gst.reconciliation.domain.run.ExcludedDocument
import com.dynokore.gst.reconciliation.domain.run.ExclusionReason
import com.dynokore.gst.reconciliation.domain.run.ProjectedEffect
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunDocumentVersion
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProjectBackfillEffectTest {
    private fun d(s: String) = BigDecimal(s)

    private val entries = listOf(MappingEntry("SR", Direction.SALE, listOf(BoxFeed("1", Basis.TAXABLE_AMOUNT))))

    private fun doc(
        uuid: String,
        amount: String,
        acknowledged: Boolean = false,
        allocation: SgdAllocation = SgdAllocation.Allocated(listOf(SgdLine(1, "SR", d(amount), d("0.00")))),
    ) = RunDocument(
        uuid,
        listOf(RunDocumentVersion(1, acknowledged, DocumentFacts(null, null), Direction.SALE, allocation)),
    )

    private val period =
        listOf(
            doc("D1", "100.00"),
            doc("D2", "-30.00"),
            doc("D3", "5.00", acknowledged = true),
            doc("D4", "0.00", allocation = SgdAllocation.NotReconcilable(NotReconcilableReason.NO_LINES)),
        )
    private val otherPeriod = listOf(doc("D5", "7.00"))

    private class Calls {
        val entryCalls = mutableListOf<List<String>>()
        val docCalls = mutableListOf<List<String>>()
    }

    private val calls = Calls()
    private val useCase =
        ProjectBackfillEffect(
            MappingEntriesSource { t, e, p ->
                calls.entryCalls += listOf(t, e, p)
                entries
            },
            RunDocumentsSource { t, e, p ->
                calls.docCalls += listOf(t, e, p)
                if (p == "2026-Q1") period else otherPeriod
            },
        )

    @Test
    fun `INV-AC-27690 the use case projects the period documents and excludes the others`() {
        val e = useCase.execute("T1", "E1", "2026-Q1", listOf("D1", "D2", "D3", "D4", "D5", "D6", "D1"))
        e shouldBe
            ProjectedEffect(
                listOf(BoxEffect("1", d("70.00"))),
                emptyList(),
                listOf(
                    ExcludedDocument("D3", ExclusionReason.ALREADY_ACKNOWLEDGED),
                    ExcludedDocument("D4", ExclusionReason.NOT_RECONCILABLE),
                    ExcludedDocument("D5", ExclusionReason.NOT_FOUND),
                    ExcludedDocument("D6", ExclusionReason.NOT_FOUND),
                ),
            )
        e shouldBe BackfillEffect.project(entries, period, listOf("D1", "D2", "D3", "D4", "D5", "D6"))
    }

    @Test
    fun `INV-AC-27690 each port is read once for the given tenant entity and period and nothing is written`() {
        useCase.execute("T1", "E1", "2026-Q1", listOf("D1"))
        calls.entryCalls shouldBe listOf(listOf("T1", "E1", "2026-Q1"))
        calls.docCalls shouldBe listOf(listOf("T1", "E1", "2026-Q1"))
        // The use case has only the two read ports as constructor parameters: it has no write port to call.
        ProjectBackfillEffect::class.java.constructors
            .single()
            .parameterTypes
            .toList() shouldBe listOf(MappingEntriesSource::class.java, RunDocumentsSource::class.java)
    }

    @Test
    fun `INV-AC-27690 an empty requested list gives an empty effect not an error`() {
        useCase.execute("T1", "E1", "2026-Q1", emptyList()) shouldBe
            ProjectedEffect(emptyList(), emptyList(), emptyList())
    }
}
