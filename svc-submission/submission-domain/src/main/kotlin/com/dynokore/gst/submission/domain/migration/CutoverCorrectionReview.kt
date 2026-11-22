package com.dynokore.gst.submission.domain.migration

import java.time.LocalDate

/** One document of an entity as the cutover-correction review sees it. */
data class ReviewCandidate(
    val documentRef: String,
    val transactionDate: LocalDate,
    val matchedOrTransmitted: Boolean,
)

/**
 * One document listed for manual review.
 *
 * @property classificationChanged true when the correction moves the document across the cutover date.
 */
data class ReviewEntry(
    val documentRef: String,
    val transactionDate: LocalDate,
    val classificationChanged: Boolean,
)

/**
 * Lists the matched or transmitted documents for manual review when the cutover date of an entity is
 * corrected (INV-REQ-1109, owner ruling 2026-11-18 option A). Documents are never changed automatically.
 * Pure: no clock, no store, the input is not modified.
 */
object CutoverCorrectionReview {
    fun list(
        oldDate: LocalDate,
        newDate: LocalDate,
        candidates: List<ReviewCandidate>,
    ): List<ReviewEntry> {
        if (oldDate == newDate) return emptyList()
        val from = if (oldDate.isBefore(newDate)) oldDate else newDate
        val to = if (oldDate.isBefore(newDate)) newDate else oldDate
        return candidates
            .filter { it.matchedOrTransmitted }
            .map {
                ReviewEntry(
                    it.documentRef,
                    it.transactionDate,
                    !it.transactionDate.isBefore(from) && it.transactionDate.isBefore(to),
                )
            }.sortedWith(compareBy<ReviewEntry> { it.transactionDate }.thenBy { it.documentRef })
    }
}
