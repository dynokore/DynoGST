package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.application.exceptions.EntityScope

/** Scoped permission key of the counterparty and amount criteria. */
const val SEARCH_CONTENT_KEY = "doc.view.content"

/** Scoped permission key of the acknowledgement identifier criterion. */
const val SEARCH_BY_ACK_KEY = "enquiry-lookup.by-acknowledgement"

/**
 * The searchDocuments read (INV-REQ-1037): limit checked first, then one decision per distinct criterion key (deny
 * or degraded refuses the whole call), then the entity scope of the caller narrowed to `entityRef`. An entity outside
 * the scope answers the empty page without a read. The page sizes come from named configuration, no default here.
 */
class SearchDocuments(
    private val scope: EntityScope,
    private val decisions: DocumentSearchDecisions,
    private val reads: DocumentIndexReads,
    private val maxLimit: Int,
    private val defaultLimit: Int,
) {
    fun search(query: SearchDocumentsQuery): SearchDocumentsResult {
        val criteria = query.criteria
        val limit = criteria.limit ?: defaultLimit
        return when {
            limit < 1 || limit > maxLimit -> SearchDocumentsResult.BadLimit
            !allowed(query) -> SearchDocumentsResult.Forbidden
            else -> read(query, limit)
        }
    }

    private fun allowed(query: SearchDocumentsQuery): Boolean =
        requiredKeys(query).all {
            decisions.decide(query.tenantId, query.principalId, it) == SearchDecision.ALLOW
        }

    private fun requiredKeys(query: SearchDocumentsQuery): Set<String> {
        val c = query.criteria
        return buildSet {
            if (c.counterparty != null || c.amountFrom != null || c.amountTo != null) add(SEARCH_CONTENT_KEY)
            if (c.acknowledgementId != null) add(SEARCH_BY_ACK_KEY)
        }
    }

    private fun read(
        query: SearchDocumentsQuery,
        limit: Int,
    ): SearchDocumentsResult {
        val granted = scope.entitiesOf(query.tenantId, query.principalId)
        val ref = query.criteria.entityRef
        val entities =
            when {
                ref == null -> granted
                ref in granted -> setOf(ref)
                else -> emptySet()
            }
        val page =
            if (entities.isEmpty()) {
                DocumentSearchPage(emptyList(), null)
            } else {
                reads.search(query.tenantId, entities, query.criteria, limit, query.criteria.cursor)
            }
        return SearchDocumentsResult.Found(page)
    }
}
