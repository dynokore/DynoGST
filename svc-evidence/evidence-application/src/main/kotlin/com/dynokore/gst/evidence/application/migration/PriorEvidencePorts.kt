package com.dynokore.gst.evidence.application.migration

import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Decides one scoped permission key for a principal on one entity of a tenant. */
fun interface PriorEvidenceAuthorizer {
    /** True only when [permission] is allowed. A false answer or an exception refuses the call. */
    fun permitted(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        permission: String,
    ): Boolean
}

/**
 * The store of imported prior evidence, used in the caller's transaction. The identity of a record in a tenant is
 * (entityRef, documentNumber, transactionDate).
 */
interface PriorEvidenceStore {
    /** Locks the identity until the transaction ends, so that concurrent imports of it serialise. */
    fun lock(
        tenant: TenantId,
        entityRef: String,
        documentNumber: String,
        transactionDate: LocalDate,
    )

    /** The stored record of the identity, or null when there is none. */
    fun find(
        tenant: TenantId,
        entityRef: String,
        documentNumber: String,
        transactionDate: LocalDate,
    ): StoredPriorEvidence?

    fun insert(
        tenant: TenantId,
        row: PriorEvidenceRow,
    )
}

/** Requests the event of one newly imported record, in the caller's transaction. */
fun interface PriorEvidenceEvents {
    fun imported(
        tenant: TenantId,
        event: PriorEvidenceImported,
    )
}

data class StoredPriorEvidence(
    val acknowledgementRef: String,
)

/** One new row of imported prior evidence. [importedBy] is the acting user ID. */
data class PriorEvidenceRow(
    val id: UUID,
    val entityRef: String,
    val periodRef: String,
    val documentNumber: String,
    val transactionDate: LocalDate,
    val acknowledgementRef: String,
    val priorSolution: String,
    val priorDocUuid: UUID?,
    val importedAt: Instant,
    val importedBy: String,
)

/** The payload of `PriorSubmissionEvidenceImported`. */
data class PriorEvidenceImported(
    val entityRef: String,
    val documentNumber: String,
    val transactionDate: LocalDate,
    val acknowledgementRef: String,
    val priorSolution: String,
)
