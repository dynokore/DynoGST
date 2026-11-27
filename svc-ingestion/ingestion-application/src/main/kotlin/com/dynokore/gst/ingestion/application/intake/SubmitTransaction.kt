package com.dynokore.gst.ingestion.application.intake

import com.dynokore.gst.ingestion.application.backfill.BackfillRequest
import com.dynokore.gst.ingestion.application.backfill.ScopeBackfill
import com.dynokore.gst.ingestion.application.backfill.ScopeBackfillCommand
import com.dynokore.gst.ingestion.application.backfill.ScopeBackfillResult
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.application.recording.RecordTransaction
import com.dynokore.gst.ingestion.domain.backfill.BackfillRefusal
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import java.time.LocalDate

/** One submitted transaction; a null [backfill] means it is not a backfill. */
data class SubmitTransactionCommand(
    val entityRef: String,
    val transactionDate: LocalDate,
    val backfill: BackfillRequest?,
    val document: RecordDocumentCommand,
)

sealed interface SubmitTransactionResult {
    data class Recorded(
        val outcome: RecordingOutcome,
    ) : SubmitTransactionResult

    /** The backfill scope rule refused the transaction: nothing was recorded. */
    data class OutOfScope(
        val reason: BackfillRefusal,
        val code: String,
    ) : SubmitTransactionResult

    /** The scope could not be read: nothing was recorded. */
    data class ScopeUnavailable(
        val reason: String,
    ) : SubmitTransactionResult
}

/** Applies the backfill scope decision before recording a transaction (INV-REQ-1086, INV-AC-27059). */
class SubmitTransaction(
    private val scope: ScopeBackfill,
    private val record: RecordTransaction,
) {
    /** @throws IllegalArgumentException as [ScopeBackfill.decide] does; nothing is recorded then. */
    fun execute(command: SubmitTransactionCommand): SubmitTransactionResult {
        val decision =
            scope.decide(ScopeBackfillCommand(command.entityRef, command.transactionDate, command.backfill))
        return settle(decision, command.document)
    }

    /** Records only what [decision] allows. Internal so that a test can hand in any decision. */
    internal fun settle(
        decision: ScopeBackfillResult,
        document: RecordDocumentCommand,
    ): SubmitTransactionResult =
        when (decision) {
            ScopeBackfillResult.NotBackfill, ScopeBackfillResult.Accepted -> {
                SubmitTransactionResult.Recorded(record.execute(document))
            }

            is ScopeBackfillResult.Refused -> {
                SubmitTransactionResult.OutOfScope(decision.reason, decision.code)
            }

            is ScopeBackfillResult.Unavailable -> {
                SubmitTransactionResult.ScopeUnavailable(decision.reason)
            }
        }
}
