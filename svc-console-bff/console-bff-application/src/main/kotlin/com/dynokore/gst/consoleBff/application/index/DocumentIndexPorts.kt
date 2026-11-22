package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.domain.index.DocumentIndexRow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The document index of the caller's tenant (INV-REQ-1037). Every method runs in the caller's (consumer's)
 * transaction. Each update returns how many rows it actually changed (a function that returns the row unchanged
 * counts 0).
 */
interface DocumentIndexStore {
    /** Inserts the row when its key is absent and returns true; an existing row is left unchanged (returns false). */
    fun upsert(row: DocumentIndexRow): Boolean

    /** Applies [change] to the row (tenant, docUuid, version); returns 1 when the row exists and changed, else 0. */
    fun update(
        tenantId: String,
        docUuid: String,
        version: Long,
        change: (DocumentIndexRow) -> DocumentIndexRow,
    ): Int

    /** Applies [change] to every row of the Doc UUID; returns the number of rows that changed. */
    fun updateDocument(
        tenantId: String,
        docUuid: String,
        change: (DocumentIndexRow) -> DocumentIndexRow,
    ): Int
}

/**
 * Facts of a CanonicalVersionCreated payload the index needs. `documentDate` is the canonical `documentDate` (the
 * document's issue date) and the only source of the document_date column; `transactionDate` is the distinct
 * `transactionDate` member (period attribution) and feeds only its own column (coordinator ruling 2026-11-22).
 * `entityRef` is null when the payload has none.
 */
data class CanonicalVersionFacts(
    val docUuid: String,
    val version: Long,
    val entityRef: String?,
    val documentNumber: String?,
    val counterpartyName: String?,
    val counterpartyUen: String?,
    val amount: BigDecimal?,
    val documentDate: LocalDate?,
    val categoryCodes: Set<String>,
    val scopeVerdict: String?,
    val transactionDate: LocalDate?,
)

/**
 * Reads the CanonicalVersionCreated payload. The counterparty is the buyer for routingFacts.direction SALE and the
 * supplier for PURCHASE; the UEN is the identifierValue whose identifierScheme is "SG_UEN". A malformed payload is a
 * `ContractViolation`.
 */
interface CanonicalVersionCreatedReader {
    fun read(dataJson: String): CanonicalVersionFacts
}

/** Facts of a SubmissionDespatched or SubmissionAcknowledged payload. `acknowledgementId` is null on despatch. */
data class SubmissionVersionFacts(
    val docUuid: String,
    val version: Long,
    val acknowledgementId: String? = null,
)

/** Reads the SubmissionDespatched payload; a malformed payload is a `ContractViolation`. */
interface SubmissionDespatchedReader {
    fun read(dataJson: String): SubmissionVersionFacts
}

/** Reads the SubmissionAcknowledged payload; a malformed payload is a `ContractViolation`. */
interface SubmissionAcknowledgedReader {
    fun read(dataJson: String): SubmissionVersionFacts
}

/** Facts of a SubmissionFailed payload the index needs: the required `version` and the optional `code`. */
data class SubmissionFailedIndexFacts(
    val docUuid: String,
    val version: Long,
    val code: String?,
)

/** Reads the SubmissionFailed payload; `code` is null when absent (never guessed). Malformed: `ContractViolation`. */
interface SubmissionFailedIndexReader {
    fun read(dataJson: String): SubmissionFailedIndexFacts
}

/**
 * Reads the MasterDataResolved payload for the index: the listed `docUuids` and the value of the first
 * `resolvedIdentifiers` item whose `scheme` equals "UEN". Either is empty or null when absent.
 */
data class ResolvedUen(
    val docUuids: List<String>,
    val uen: String?,
)

interface MasterDataResolvedUenReader {
    fun read(dataJson: String): ResolvedUen
}

/** Counters of the index writer. */
interface IndexMetrics {
    /** A CanonicalVersionCreated event had no `entityRef`, so no searchable row was written (fail closed). */
    fun unscopedVersionEvent()
}
