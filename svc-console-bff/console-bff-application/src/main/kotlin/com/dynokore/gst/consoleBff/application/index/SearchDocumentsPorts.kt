package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.domain.index.DocumentSearchCriteria
import com.dynokore.gst.consoleBff.domain.index.IndexState
import java.time.LocalDate

/** One search hit: identifiers and searchable attributes only (contract DocumentSearchHit). */
data class DocumentSearchHit(
    val docUuid: String,
    val entityRef: String,
    val documentNumber: String?,
    val version: Long,
    val state: IndexState,
    val acknowledgementId: String?,
    val issueDate: LocalDate?,
)

/** A page of hits; [nextCursor] is opaque and null on the last page. */
data class DocumentSearchPage(
    val items: List<DocumentSearchHit>,
    val nextCursor: String?,
)

/**
 * Read port of the document index. Rows are ordered by document date ascending (null last), then Doc UUID, then
 * version; the cursor encodes a null date so a page never repeats a hit. The adapter applies [entityRefs] in the
 * WHERE clause as well as the row level security.
 */
interface DocumentIndexReads {
    fun search(
        tenantId: String,
        entityRefs: Set<String>,
        criteria: DocumentSearchCriteria,
        limit: Int,
        cursor: String?,
    ): DocumentSearchPage
}

/** Outcome of one permission decision; only [ALLOW] lets the call go on. */
enum class SearchDecision { ALLOW, DENY, DEGRADED }

/** Decided per request by AuthorityReplica.decide in the adapter for a scoped key; no role is read. */
fun interface DocumentSearchDecisions {
    fun decide(
        tenantId: String,
        principalId: String,
        permissionKey: String,
    ): SearchDecision
}

data class SearchDocumentsQuery(
    val tenantId: String,
    val principalId: String,
    val criteria: DocumentSearchCriteria,
)

sealed interface SearchDocumentsResult {
    data class Found(
        val page: DocumentSearchPage,
    ) : SearchDocumentsResult

    /** The existing 403 refusal: a decision was deny or degraded. */
    data object Forbidden : SearchDocumentsResult

    /** The existing validation refusal of the sibling use case (ListExceptions): limit out of range. */
    data object BadLimit : SearchDocumentsResult
}
