package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import java.time.Instant
import java.util.UUID

/**
 * The intake operation of a receipt. The declared order is the order of the listing: `BATCH` before `TRANSACTIONS`.
 */
enum class ListedIntakeOperation { BATCH, TRANSACTIONS }

/** The receipts [submittedBy] recorded, [recordedFrom] included and [recordedTo] excluded. */
data class IntakeListingFilter(
    val submittedBy: String,
    val recordedFrom: Instant,
    val recordedTo: Instant,
)

/** The position after which a page starts: the whole sort key of the last item of the page before. */
data class IntakeListingPosition(
    val sourceKey: String,
    val recordedAt: Instant,
    val sourceRevision: Long,
    val entityRef: String,
    val intakeOperation: ListedIntakeOperation,
)

/** One intake receipt with the aggregates known to hold the data of its operation; it carries no invoice content. */
data class ListedIntake(
    val intakeOperation: ListedIntakeOperation,
    val entityRef: String,
    val sourceKey: String,
    val sourceRevision: Long,
    val aggregateType: AggregateType,
    val recordedAt: Instant,
    /** Distinct; empty while no aggregate holds the data of the receipt. */
    val aggregateIds: List<UUID>,
)

data class IntakeListingPage(
    val items: List<ListedIntake>,
    /** The position of the last item when more items follow; null on the last page. */
    val next: IntakeListingPosition?,
)

interface IntakeListingReader {
    /** The distinct entityRefs of the receipts of [filter]: the entities the caller needs a decision on. */
    fun entityRefs(filter: IntakeListingFilter): Set<String>

    /**
     * The page of the receipts of [filter] in the entities [permittedEntityRefs] only, filtered before [limit], in the
     * order `sourceKey`, `recordedAt`, `sourceRevision`, `entityRef`, `intakeOperation`, ascending. No permitted
     * entity gives the empty page without a statement.
     */
    fun page(
        filter: IntakeListingFilter,
        permittedEntityRefs: Set<String>,
        after: IntakeListingPosition?,
        limit: Int,
    ): IntakeListingPage
}

/**
 * The listing of what a principal submitted in a recording window, read from the system of record (INV-REQ-867;
 * INV-AC-28765). It decides no permission: the caller reads [entityRefs], decides each of them, and passes only the
 * permitted ones to [page].
 */
class ListSubmittedIntake(
    private val reader: IntakeListingReader,
) {
    fun entityRefs(filter: IntakeListingFilter): Set<String> = reader.entityRefs(filter)

    fun page(
        filter: IntakeListingFilter,
        permittedEntityRefs: Set<String>,
        after: IntakeListingPosition?,
        limit: Int,
    ): IntakeListingPage = reader.page(filter, permittedEntityRefs, after, limit)
}
