package com.dynokore.gst.tenancy.application.period

import java.time.LocalDate

/** The permission key of getSubmissionScope: the catalogue row "Ingest transactions via API"; decided per request, never by role. */
object SubmissionScopePermissions {
    const val READ = "transaction.ingest"
}

/** What the store holds for one entity: the stored `go_live_date` and the period records in force (start and end). */
data class SubmissionScopeRecord(
    val goLiveDate: LocalDate?,
    val periodsInForce: List<SubmissionPeriod>,
)

/** The start and end of one period record, both ends inclusive. */
data class SubmissionPeriod(
    val start: LocalDate,
    val end: LocalDate,
)

/** The port the HTTP slice implements; null when the entity is unknown. */
fun interface SubmissionScopeStore {
    fun find(entityRef: String): SubmissionScopeRecord?
}

sealed interface SubmissionScopeOutcome {
    data class Done(
        val entityRef: String,
        val activatedOn: LocalDate?,
        val current: SubmissionPeriod?,
    ) : SubmissionScopeOutcome

    data object NotFound : SubmissionScopeOutcome
}

/**
 * getSubmissionScope (INV-REQ-1086): the stored go-live date and the one period record in force whose start is not after
 * [today] and whose end is not before it. [today] is the Singapore calendar day ([SINGAPORE]), the same day the period
 * list uses; the caller passes it so the clock is a single source.
 */
class GetSubmissionScope(
    private val store: SubmissionScopeStore,
) {
    fun get(
        entityRef: String,
        today: LocalDate,
    ): SubmissionScopeOutcome {
        val record = store.find(entityRef) ?: return SubmissionScopeOutcome.NotFound
        val current = record.periodsInForce.firstOrNull { !it.start.isAfter(today) && !it.end.isBefore(today) }
        return SubmissionScopeOutcome.Done(entityRef, record.goLiveDate, current)
    }
}
