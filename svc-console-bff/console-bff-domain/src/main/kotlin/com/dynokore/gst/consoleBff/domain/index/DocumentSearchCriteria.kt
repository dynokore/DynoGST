package com.dynokore.gst.consoleBff.domain.index

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The fourteen parameters of the searchDocuments operation after validation (INV-REQ-1037). A null member is a
 * criterion not given. [limit] null means the configured default page size; [cursor] is opaque to this type.
 */
data class DocumentSearchCriteria(
    val entityRef: String? = null,
    val documentNumber: String? = null,
    val docUuid: String? = null,
    val acknowledgementId: String? = null,
    val counterparty: String? = null,
    val amountFrom: BigDecimal? = null,
    val amountTo: BigDecimal? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val state: IndexState? = null,
    val categoryCode: String? = null,
    val errorCode: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)
