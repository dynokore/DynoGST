package com.dynokore.gst.submission.domain.migration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CutoverCorrectionReviewTest {
    private val feb1 = LocalDate.of(2026, 2, 1)
    private val mar1 = LocalDate.of(2026, 3, 1)

    private fun c(
        ref: String,
        date: LocalDate,
        m: Boolean = true,
    ) = ReviewCandidate(ref, date, m)

    private val inputs =
        listOf(
            c("D5", LocalDate.of(2026, 4, 1)),
            c("D3", LocalDate.of(2026, 2, 20)),
            c("D1", LocalDate.of(2026, 1, 15)),
            c("D4", mar1),
            c("D2", feb1),
            c("X", LocalDate.of(2026, 2, 10), false),
        )

    @Test
    fun `INV-AC-24886 criterion inputs list matched documents ordered with flags`() {
        val copy = inputs.toList()
        val r = CutoverCorrectionReview.list(mar1, feb1, inputs)
        r shouldBe
            listOf(
                ReviewEntry("D1", LocalDate.of(2026, 1, 15), false),
                ReviewEntry("D2", feb1, true),
                ReviewEntry("D3", LocalDate.of(2026, 2, 20), true),
                ReviewEntry("D4", mar1, false),
                ReviewEntry("D5", LocalDate.of(2026, 4, 1), false),
            )
        inputs shouldBe copy
    }

    @Test
    fun `INV-AC-24886 date equal to newDate is changed and date equal to oldDate is not`() {
        val r = CutoverCorrectionReview.list(mar1, feb1, listOf(c("A", feb1), c("B", mar1)))
        r.map { it.classificationChanged } shouldBe listOf(true, false)
    }

    @Test
    fun `INV-AC-24886 reverse direction gives the same flags`() {
        CutoverCorrectionReview.list(feb1, mar1, inputs) shouldBe CutoverCorrectionReview.list(mar1, feb1, inputs)
    }

    @Test
    fun `INV-AC-24886 empty input gives empty list`() {
        CutoverCorrectionReview.list(mar1, feb1, emptyList()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24886 equal dates give empty list`() {
        CutoverCorrectionReview.list(mar1, mar1, inputs) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24886 same date is ordered by documentRef`() {
        val r = CutoverCorrectionReview.list(mar1, feb1, listOf(c("B", feb1), c("A", feb1)))
        r.map { it.documentRef } shouldBe listOf("A", "B")
    }

    @Test
    fun `INV-AC-24886 unmatched candidate inside the range is absent`() {
        CutoverCorrectionReview.list(mar1, feb1, listOf(c("X", LocalDate.of(2026, 2, 10), false))) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24886 duplicate documentRef appears as many times as given`() {
        CutoverCorrectionReview.list(mar1, feb1, listOf(c("A", feb1), c("A", feb1))).size shouldBe 2
    }
}
