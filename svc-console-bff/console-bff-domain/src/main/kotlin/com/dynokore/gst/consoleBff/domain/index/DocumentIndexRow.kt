package com.dynokore.gst.consoleBff.domain.index

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Published lifecycle words of `submission/schemas/lifecycle-states.schema.json` that the document index uses.
 * `rank` orders the states a row moves through: READY, NOT_SUBMITTABLE and HELD share the lowest rank, SENT is above
 * them and ACKNOWLEDGED is the highest. A later event never lowers a row to a lower rank (INV-AC-26807).
 */
enum class IndexState(
    val rank: Int,
) {
    READY(0),
    NOT_SUBMITTABLE(0),
    HELD(0),
    SENT(1),
    ACKNOWLEDGED(2),
}

/**
 * One document index row, key (tenantId, docUuid, version) (INV-REQ-1037). It holds identifiers and searchable
 * attributes only, no invoice content beyond them. `entityRef` is never blank: a version event without it
 * writes no row.
 */
data class DocumentIndexRow(
    val tenantId: String,
    val docUuid: String,
    val version: Long,
    val entityRef: String,
    val documentNumber: String?,
    val counterpartyName: String?,
    val counterpartyUen: String?,
    val amount: BigDecimal?,
    val documentDate: LocalDate?,
    val categoryCodes: Set<String>,
    val state: IndexState,
    val acknowledgementId: String?,
    val errorCode: String?,
    val transactionDate: LocalDate?,
)
