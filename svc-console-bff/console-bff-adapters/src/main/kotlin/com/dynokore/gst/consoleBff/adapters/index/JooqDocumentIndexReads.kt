package com.dynokore.gst.consoleBff.adapters.index

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.records.DocumentIndexRecord
import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DOCUMENT_INDEX
import com.dynokore.gst.consoleBff.application.index.DocumentIndexReads
import com.dynokore.gst.consoleBff.application.index.DocumentSearchHit
import com.dynokore.gst.consoleBff.application.index.DocumentSearchPage
import com.dynokore.gst.consoleBff.domain.index.DocumentSearchCriteria
import com.dynokore.gst.consoleBff.domain.index.IndexState
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.ResultQuery
import org.jooq.impl.DSL
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * The search read of `console_bff.document_index` (INV-REQ-1037, INV-AC-26808). Every criterion is a bound parameter,
 * never concatenated. The statement always names the tenant and the entity set (`entity_ref = any(?)`) in its WHERE
 * clause, in addition to the forced row level security (NIST SP 800-53 AC-3, defence in depth); an empty entity set
 * answers the empty page without a statement. A date range matches only rows with a document date. Order: document
 * date ascending with null last, then Doc UUID, then version; the keyset cursor encodes a null date.
 */
@Suppress("SpreadOperator") // the entity set is bound as one array parameter, copied once per search
class JooqDocumentIndexReads(
    private val dsl: DSLContext,
) : DocumentIndexReads {
    override fun search(
        tenantId: String,
        entityRefs: Set<String>,
        criteria: DocumentSearchCriteria,
        limit: Int,
        cursor: String?,
    ): DocumentSearchPage {
        val statement = statement(tenantId, entityRefs, criteria, limit, cursor) ?: return EMPTY
        val rows = statement.fetch()
        val hits = rows.take(limit).map { it.toHit() }
        val last = rows.getOrNull(limit - 1)
        val next = if (rows.size > limit && last != null) last.toCursor().encode() else null
        return DocumentSearchPage(hits, next)
    }

    /**
     * The one statement [search] runs, or null when no row can match (an empty entity set, or a Doc UUID that is no
     * UUID). Its WHERE clause names the tenant and the entity set (`entity_ref = any(?)`) in every case.
     */
    internal fun statement(
        tenantId: String,
        entityRefs: Set<String>,
        criteria: DocumentSearchCriteria,
        limit: Int,
        cursor: String?,
    ): ResultQuery<DocumentIndexRecord>? {
        require(limit >= 1) { "limit must be at least 1" }
        val docUuid = criteria.docUuid?.let { uuidOrNull(it) ?: return null }
        if (entityRefs.isEmpty()) return null
        val conditions =
            listOf(
                DOCUMENT_INDEX.TENANT_ID.eq(UUID.fromString(tenantId)),
                DOCUMENT_INDEX.ENTITY_REF.eq(DSL.any(*entityRefs.sorted().toTypedArray())),
            ) + criteriaConditions(criteria, docUuid) + listOfNotNull(cursor?.let { after(Cursor.decode(it)) })
        return dsl
            .selectFrom(DOCUMENT_INDEX)
            .where(conditions)
            .orderBy(
                DOCUMENT_INDEX.DOCUMENT_DATE.asc().nullsLast(),
                DOCUMENT_INDEX.DOC_UUID.asc(),
                DOCUMENT_INDEX.VERSION.asc(),
            ).limit(limit + 1)
    }

    /** One bound-parameter condition per criterion given; no value is concatenated into the statement. */
    private fun criteriaConditions(
        criteria: DocumentSearchCriteria,
        docUuid: UUID?,
    ): List<Condition> =
        listOfNotNull(
            criteria.entityRef?.let { DOCUMENT_INDEX.ENTITY_REF.eq(it) },
            criteria.documentNumber?.let { DOCUMENT_INDEX.DOCUMENT_NUMBER.eq(it) },
            docUuid?.let { DOCUMENT_INDEX.DOC_UUID.eq(it) },
            criteria.acknowledgementId?.let { DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID.eq(it) },
            criteria.counterparty?.let {
                DOCUMENT_INDEX.COUNTERPARTY_NAME.containsIgnoreCase(it).or(DOCUMENT_INDEX.COUNTERPARTY_UEN.eq(it))
            },
            criteria.amountFrom?.let { DOCUMENT_INDEX.AMOUNT.ge(it) },
            criteria.amountTo?.let { DOCUMENT_INDEX.AMOUNT.le(it) },
            criteria.dateFrom?.let { DOCUMENT_INDEX.DOCUMENT_DATE.ge(it) },
            criteria.dateTo?.let { DOCUMENT_INDEX.DOCUMENT_DATE.le(it) },
            criteria.state?.let { DOCUMENT_INDEX.STATE.eq(it.name) },
            criteria.categoryCode?.let { DOCUMENT_INDEX.CATEGORY_CODES.contains(arrayOf(it)) },
            criteria.errorCode?.let { DOCUMENT_INDEX.ERROR_CODE.eq(it) },
        )

    private fun DocumentIndexRecord.toHit() =
        DocumentSearchHit(
            docUuid = checkNotNull(docUuid).toString(),
            entityRef = checkNotNull(entityRef),
            documentNumber = documentNumber,
            version = checkNotNull(version),
            state = IndexState.valueOf(checkNotNull(state)),
            acknowledgementId = acknowledgementId,
            issueDate = documentDate,
        )

    private fun DocumentIndexRecord.toCursor() = Cursor(documentDate, checkNotNull(docUuid), checkNotNull(version))

    /** Rows strictly after the cursor in the order (date asc nulls last, doc_uuid, version). */
    private fun after(c: Cursor): Condition {
        val tail =
            DOCUMENT_INDEX.DOC_UUID
                .gt(c.docUuid)
                .or(DOCUMENT_INDEX.DOC_UUID.eq(c.docUuid).and(DOCUMENT_INDEX.VERSION.gt(c.version)))
        val date = c.date
        return if (date == null) {
            DOCUMENT_INDEX.DOCUMENT_DATE.isNull.and(tail)
        } else {
            DOCUMENT_INDEX.DOCUMENT_DATE
                .gt(date)
                .or(DOCUMENT_INDEX.DOCUMENT_DATE.isNull)
                .or(DOCUMENT_INDEX.DOCUMENT_DATE.eq(date).and(tail))
        }
    }

    private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()

    /** Opaque keyset position: the date (or `-` when null), the Doc UUID and the version. */
    private data class Cursor(
        val date: LocalDate?,
        val docUuid: UUID,
        val version: Long,
    ) {
        fun encode(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString("${date ?: "-"}|$docUuid|$version".toByteArray())

        companion object {
            private const val PARTS = 3

            fun decode(text: String): Cursor =
                try {
                    val parts = String(Base64.getUrlDecoder().decode(text)).split('|')
                    require(parts.size == PARTS)
                    Cursor(
                        if (parts[0] == "-") null else LocalDate.parse(parts[0]),
                        UUID.fromString(parts[1]),
                        parts[2].toLong(),
                    )
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("the cursor is not one this search issued", e)
                } catch (e: DateTimeException) {
                    throw IllegalArgumentException("the cursor is not one this search issued", e)
                }
        }
    }
}

private val EMPTY = DocumentSearchPage(emptyList(), null)
