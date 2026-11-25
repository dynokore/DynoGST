package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/** The members of a `PriorSubmissionEvidenceImported` event the consumer reads. */
data class PriorEvidenceImportedData(
    val entityRef: String,
    val documentNumber: String,
    val transactionDate: LocalDate,
    val acknowledgementRef: String,
    val priorSolution: String,
)

/** Reads the event data JSON of a `PriorSubmissionEvidenceImported` (the JSON library stays in the adapters). */
fun interface PriorEvidenceImportedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): PriorEvidenceImportedData
}

/**
 * Keeps the imported prior submission evidence (INV-REQ-1109, INV-T-28454). An identical repeat is IGNORED; the same
 * (entity, document number, transaction date) with another acknowledgement reference, or a malformed event, is a
 * [ContractViolation] and stores nothing. No message carries a field value.
 */
class PriorEvidenceImportedHandler(
    private val tenants: TenantContext,
    private val reader: PriorEvidenceImportedDataReader,
    private val store: ImportedPriorEvidenceStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val record =
            try {
                ImportedPriorRecord(data.entityRef, data.documentNumber, data.transactionDate, data.acknowledgementRef)
            } catch (e: IllegalArgumentException) {
                throw ContractViolation("PriorSubmissionEvidenceImported member is invalid: ${e.message}", e)
            }
        if (data.priorSolution.isBlank()) {
            throw ContractViolation("PriorSubmissionEvidenceImported member priorSolution is blank")
        }
        return if (store.record(tenants.current(), record, data.priorSolution)) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
