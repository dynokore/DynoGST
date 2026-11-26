package com.dynokore.gst.ingestion.application.backfill

import com.dynokore.gst.ingestion.domain.backfill.BackfillDecision
import com.dynokore.gst.ingestion.domain.backfill.BackfillRefusal
import com.dynokore.gst.ingestion.domain.backfill.BackfillScope
import com.dynokore.gst.ingestion.domain.backfill.BackfillSituation
import com.dynokore.gst.ingestion.domain.backfill.DatePeriod
import java.time.LocalDate

/** The answer of the service that owns the entity's submission activation date and current period. */
sealed interface SubmissionScopeRead {
    /** [activatedOn] is null while submission is not activated; [current] is null when there is no current period. */
    data class Answer(
        val activatedOn: LocalDate?,
        val current: DatePeriod?,
    ) : SubmissionScopeRead

    /** The owning service gave no answer. */
    data class Unavailable(
        val reason: String,
    ) : SubmissionScopeRead
}

/** The submission-scope port: reads the two values ingestion-svc does not own (INV-REQ-1086). */
fun interface SubmissionScopeSource {
    fun read(entityRef: String): SubmissionScopeRead
}

/** The affected period as the request carries it, not yet validated. */
data class RequestedPeriod(
    val start: LocalDate,
    val end: LocalDate,
)

/** The `backfill` object of a request; [situation] is the key as sent. */
data class BackfillRequest(
    val situation: String,
    val affectedPeriod: RequestedPeriod?,
)

/** One transaction at intake; a request without [backfill] is not a backfill. */
data class ScopeBackfillCommand(
    val entityRef: String,
    val transactionDate: LocalDate,
    val backfill: BackfillRequest?,
)

sealed interface ScopeBackfillResult {
    object NotBackfill : ScopeBackfillResult

    object Accepted : ScopeBackfillResult

    data class Refused(
        val reason: BackfillRefusal,
        val code: String = "ING-025",
    ) : ScopeBackfillResult

    /** The scope could not be read: the transaction must not be recorded. */
    data class Unavailable(
        val reason: String,
    ) : ScopeBackfillResult
}

/**
 * Applies the backfill scope rule at intake, before `RecordTransaction` (INV-REQ-1086). Every date comparison is
 * [BackfillScope.decide]. The port is read only for a situation that compares a value ingestion-svc does not own, and
 * an unanswered read is never accepted.
 */
class ScopeBackfill(
    private val source: SubmissionScopeSource,
) {
    /** @throws IllegalArgumentException for an unknown situation key or a period that starts after its end. */
    fun decide(command: ScopeBackfillCommand): ScopeBackfillResult {
        val backfill = command.backfill ?: return ScopeBackfillResult.NotBackfill
        val situation = BackfillSituation.fromKey(backfill.situation)
        val affected = backfill.affectedPeriod?.let { DatePeriod(it.start, it.end) }
        return when (situation) {
            BackfillSituation.SILENT_FAILURE_CORRECTION -> {
                apply(situation, command.transactionDate, affected)
            }

            BackfillSituation.EARLY_ADOPTION -> {
                withScope(command.entityRef) { scope ->
                    scope.activatedOn?.let { apply(situation, command.transactionDate, affected, activationDate = it) }
                        ?: ScopeBackfillResult.Refused(BackfillRefusal.BEFORE_ACTIVATION)
                }
            }

            BackfillSituation.MID_PERIOD_MIGRATION -> {
                withScope(command.entityRef) { scope ->
                    scope.current?.let { apply(situation, command.transactionDate, affected, currentPeriod = it) }
                        ?: ScopeBackfillResult.Refused(BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
                }
            }
        }
    }

    private fun withScope(
        entityRef: String,
        onAnswer: (SubmissionScopeRead.Answer) -> ScopeBackfillResult,
    ): ScopeBackfillResult =
        when (val read = source.read(entityRef)) {
            is SubmissionScopeRead.Answer -> onAnswer(read)
            is SubmissionScopeRead.Unavailable -> ScopeBackfillResult.Unavailable(read.reason)
        }

    /** The defaults stand for a value the situation does not compare, so the rule never reads them. */
    private fun apply(
        situation: BackfillSituation,
        transactionDate: LocalDate,
        affectedPeriod: DatePeriod?,
        activationDate: LocalDate = transactionDate,
        currentPeriod: DatePeriod = DatePeriod(transactionDate, transactionDate),
    ): ScopeBackfillResult =
        when (
            val decision =
                BackfillScope.decide(situation, transactionDate, activationDate, affectedPeriod, currentPeriod)
        ) {
            BackfillDecision.Accepted -> ScopeBackfillResult.Accepted
            is BackfillDecision.Refused -> ScopeBackfillResult.Refused(decision.reason)
        }
}
