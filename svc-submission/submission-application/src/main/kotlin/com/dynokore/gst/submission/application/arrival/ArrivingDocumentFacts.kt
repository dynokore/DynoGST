package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The document facts of an arriving version that the prior-solution check needs (INV-REQ-1110). */
data class ArrivingDocument(
    val entityRef: String,
    val documentNumber: String?,
    val transactionDate: LocalDate,
    val creditNote: Boolean,
    val precedingDocumentNumber: String?,
    val precedingDocumentDate: LocalDate?,
    val versions: List<LifecycleState>,
)

/**
 * Port: the facts of the arriving version. Null means a required fact is missing now (the caller treats it as
 * unavailable).
 */
interface ArrivingDocumentFacts {
    fun of(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): ArrivingDocument?
}
