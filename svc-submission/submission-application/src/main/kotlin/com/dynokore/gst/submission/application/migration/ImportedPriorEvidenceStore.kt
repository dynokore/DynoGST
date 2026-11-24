package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.platform.tenancykernel.TenantId

/** The prior submission evidence kept per tenant from `PriorSubmissionEvidenceImported` (INV-REQ-1109, INV-T-28454). */
interface ImportedPriorEvidenceStore {
    /**
     * Stores [record] with its [priorSolution] for [tenant] once per (entity, document number, transaction date).
     * Returns true when a row was stored and false for an identical repeat.
     *
     * @throws com.dynokore.platform.outboxandqueue.ContractViolation when a row with the same key holds another
     *   acknowledgement reference; nothing is stored then.
     */
    fun record(
        tenant: TenantId,
        record: ImportedPriorRecord,
        priorSolution: String,
    ): Boolean

    /** The imported records of [entityRef] under [tenant] only. */
    fun listFor(
        tenant: TenantId,
        entityRef: String,
    ): List<ImportedPriorRecord>
}
