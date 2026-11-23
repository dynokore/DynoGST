package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The outcome of [ListRenditionAttestations.list]. */
sealed interface ListRenditionOutcome {
    data class Page(
        val page: RenditionHistoryPage,
    ) : ListRenditionOutcome

    data class Forbidden(
        val reason: String,
    ) : ListRenditionOutcome

    data object NotFound : ListRenditionOutcome

    data class Invalid(
        val reason: String,
    ) : ListRenditionOutcome
}

/**
 * listRenditionAttestations (INV-REQ-1036): the paged attestation history of an entity. The permission is checked
 * first, per request and fail closed ([PeriodAccess]); the store is read only afterwards. Nothing is written.
 */
class ListRenditionAttestations(
    private val tx: TransactionRunner,
    private val access: PeriodAccess,
    private val entities: RenditionEntities,
    private val history: RenditionAttestationHistory,
) {
    fun list(
        entityRef: String,
        limit: Int,
        after: RenditionHistoryPosition?,
    ): ListRenditionOutcome {
        access.denial(entityRef, RenditionPermissions.EVIDENCE_VIEW)?.let { return ListRenditionOutcome.Forbidden(it) }
        return if (limit !in MIN_LIMIT..MAX_LIMIT) {
            ListRenditionOutcome.Invalid("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        } else {
            tx.inTransaction {
                if (entities.exists(entityRef)) {
                    ListRenditionOutcome.Page(history.page(entityRef, limit, after))
                } else {
                    ListRenditionOutcome.NotFound
                }
            }
        }
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 500
    }
}
