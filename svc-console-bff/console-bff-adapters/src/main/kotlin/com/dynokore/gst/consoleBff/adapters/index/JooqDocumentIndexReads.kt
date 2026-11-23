package com.dynokore.gst.consoleBff.adapters.index

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DOCUMENT_INDEX
import com.dynokore.gst.consoleBff.application.index.DocumentIndexReads
import com.dynokore.gst.consoleBff.application.index.DocumentSearchHit
import com.dynokore.gst.consoleBff.application.index.DocumentSearchPage
import com.dynokore.gst.consoleBff.domain.index.DocumentSearchCriteria
import com.dynokore.gst.consoleBff.domain.index.IndexState
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
        require(limit >= 1) { "limit must be at least 1" }
        if (entityRefs.isEmpty()) return DocumentSearchPage(emptyList(), null)
        val docUuid = criteria.docUuid?.let { text -> uuidOrNull(text) ?: return DocumentSearchPage(emptyList(), null) }
        val conditions =
            buildList {
                add(DOCUMENT_INDEX.TENANT_ID.eq(UUID.fromString(tenantId)))
                add(DOCUMENT_INDEX.ENTITY_REF.eq(DSL.any(*entityRefs.sorted().toTypedArray())))
                criteria.entityRef?.let { add(DOCUMENT_INDEX.ENTITY_REF.eq(it)) }
                criteria.documentNumber?.let { add(DOCUMENT_INDEX.DOCUMENT_NUMBER.eq(it)) }
                docUuid?.let { add(DOCUMENT_INDEX.DOC_UUID.eq(it)) }
                criteria.acknowledgementId?.let { add(DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID.eq(it)) }
                criteria.counterparty?.let {
                    add(
                        DOCUMENT_INDEX.COUNTERPARTY_NAME
                            .containsIgnoreCase(
                                it,
                            ).or(DOCUMENT_INDEX.COUNTERPARTY_UEN.eq(it)),
                    )
                }
                criteria.amountFrom?.let { add(DOCUMENT_INDEX.AMOUNT.ge(it)) }
                criteria.amountTo?.let { add(DOCUMENT_INDEX.AMOUNT.le(it)) }
                criteria.dateFrom?.let { add(DOCUMENT_INDEX.DOCUMENT_DATE.ge(it)) }
                criteria.dateTo?.let { add(DOCUMENT_INDEX.DOCUMENT_DATE.le(it)) }
                criteria.state?.let { add(DOCUMENT_INDEX.STATE.eq(it.name)) }
                criteria.categoryCode?.let {
                    add(
                        DSL.condition("{0} = any({1})", DSL.`val`(it), DOCUMENT_INDEX.CATEGORY_CODES),
                    )
                }
                criteria.errorCode?.let { add(DOCUMENT_INDEX.ERROR_CODE.eq(it)) }
                cursor?.let { add(after(Cursor.decode(it))) }
            }
        val rows =
            dsl
                .selectFrom(DOCUMENT_INDEX)
                .where(conditions)
                .orderBy(
                    DOCUMENT_INDEX.DOCUMENT_DATE.asc().nullsLast(),
                    DOCUMENT_INDEX.DOC_UUID.asc(),
                    DOCUMENT_INDEX.VERSION.asc(),
                ).limit(limit + 1)
                .fetch()
        val hits =
            rows.take(limit).map { r ->
                DocumentSearchHit(
                    docUuid = checkNotNull(r.get(DOCUMENT_INDEX.DOC_UUID)).toString(),
                    entityRef = checkNotNull(r.get(DOCUMENT_INDEX.ENTITY_REF)),
                    documentNumber = r.get(DOCUMENT_INDEX.DOCUMENT_NUMBER),
                    version = checkNotNull(r.get(DOCUMENT_INDEX.VERSION)),
                    state = IndexState.valueOf(checkNotNull(r.get(DOCUMENT_INDEX.STATE))),
                    acknowledgementId = r.get(DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID),
                    issueDate = r.get(DOCUMENT_INDEX.DOCUMENT_DATE),
                )
            }
        val last = rows.getOrNull(limit - 1)
        val next =
            if (rows.size > limit && last != null) {
                Cursor(
                    last.get(DOCUMENT_INDEX.DOCUMENT_DATE),
                    checkNotNull(last.get(DOCUMENT_INDEX.DOC_UUID)),
                    checkNotNull(last.get(DOCUMENT_INDEX.VERSION)),
                ).encode()
            } else {
                null
            }
        return DocumentSearchPage(hits, next)
    }

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
            fun decode(text: String): Cursor =
                try {
                    val parts = String(Base64.getUrlDecoder().decode(text)).split('|')
                    require(parts.size == 3)
                    Cursor(
                        if (parts[0] == "-") null else LocalDate.parse(parts[0]),
                        UUID.fromString(parts[1]),
                        parts[2].toLong(),
                    )
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException("the cursor is not one this search issued", e)
                }
        }
    }
}
