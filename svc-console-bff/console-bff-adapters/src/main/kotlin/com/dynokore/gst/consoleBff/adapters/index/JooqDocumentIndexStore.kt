package com.dynokore.gst.consoleBff.adapters.index

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.DOCUMENT_INDEX
import com.dynokore.gst.consoleBff.application.index.DocumentIndexStore
import com.dynokore.gst.consoleBff.domain.index.DocumentIndexRow
import com.dynokore.gst.consoleBff.domain.index.IndexState
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import java.util.UUID

/**
 * `console_bff.document_index` through the jOOQ DSL (TPL-11). Isolation is the forced row level security under the
 * caller's signed tenant binding; every statement also names the tenant (defence in depth, NIST SP 800-53 AC-3).
 * Updates read the row `for update` inside the consumer's transaction, apply the pure change and write only a row that
 * changed, so the count is the number of rows actually changed.
 */
class JooqDocumentIndexStore(
    private val dsl: DSLContext,
) : DocumentIndexStore {
    override fun upsert(row: DocumentIndexRow): Boolean =
        dsl
            .insertInto(DOCUMENT_INDEX)
            .set(DOCUMENT_INDEX.TENANT_ID, UUID.fromString(row.tenantId))
            .set(DOCUMENT_INDEX.DOC_UUID, UUID.fromString(row.docUuid))
            .set(DOCUMENT_INDEX.VERSION, row.version)
            .set(DOCUMENT_INDEX.ENTITY_REF, row.entityRef)
            .set(DOCUMENT_INDEX.DOCUMENT_NUMBER, row.documentNumber)
            .set(DOCUMENT_INDEX.COUNTERPARTY_NAME, row.counterpartyName)
            .set(DOCUMENT_INDEX.COUNTERPARTY_UEN, row.counterpartyUen)
            .set(DOCUMENT_INDEX.AMOUNT, row.amount)
            .set(DOCUMENT_INDEX.DOCUMENT_DATE, row.documentDate)
            .set(DOCUMENT_INDEX.TRANSACTION_DATE, requireNotNull(row.transactionDate) { "transactionDate is required" })
            .set(DOCUMENT_INDEX.CATEGORY_CODES, row.categoryCodes.sorted().toTypedArray())
            .set(DOCUMENT_INDEX.STATE, row.state.name)
            .set(DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID, row.acknowledgementId)
            .set(DOCUMENT_INDEX.ERROR_CODE, row.errorCode)
            .onConflict(DOCUMENT_INDEX.TENANT_ID, DOCUMENT_INDEX.DOC_UUID, DOCUMENT_INDEX.VERSION)
            .doNothing()
            .execute() == 1

    override fun update(
        tenantId: String,
        docUuid: String,
        version: Long,
        change: (DocumentIndexRow) -> DocumentIndexRow,
    ): Int {
        val doc = uuidOrNull(docUuid) ?: return 0
        return apply(
            DOCUMENT_INDEX.TENANT_ID
                .eq(UUID.fromString(tenantId))
                .and(DOCUMENT_INDEX.DOC_UUID.eq(doc))
                .and(DOCUMENT_INDEX.VERSION.eq(version)),
            change,
        )
    }

    override fun updateDocument(
        tenantId: String,
        docUuid: String,
        change: (DocumentIndexRow) -> DocumentIndexRow,
    ): Int {
        val doc = uuidOrNull(docUuid) ?: return 0
        return apply(
            DOCUMENT_INDEX.TENANT_ID.eq(UUID.fromString(tenantId)).and(DOCUMENT_INDEX.DOC_UUID.eq(doc)),
            change,
        )
    }

    private fun apply(
        where: org.jooq.Condition,
        change: (DocumentIndexRow) -> DocumentIndexRow,
    ): Int {
        var changed = 0
        for (record in dsl
            .selectFrom(DOCUMENT_INDEX)
            .where(where)
            .forUpdate()
            .fetch()) {
            val before = record.toRow()
            val after = change(before)
            if (after == before) continue
            check(
                after.tenantId == before.tenantId && after.docUuid == before.docUuid && after.version == before.version,
            ) {
                "a change must not alter the row key"
            }
            val n =
                dsl
                    .update(DOCUMENT_INDEX)
                    .set(DOCUMENT_INDEX.ENTITY_REF, after.entityRef)
                    .set(DOCUMENT_INDEX.DOCUMENT_NUMBER, after.documentNumber)
                    .set(DOCUMENT_INDEX.COUNTERPARTY_NAME, after.counterpartyName)
                    .set(DOCUMENT_INDEX.COUNTERPARTY_UEN, after.counterpartyUen)
                    .set(DOCUMENT_INDEX.AMOUNT, after.amount)
                    .set(DOCUMENT_INDEX.DOCUMENT_DATE, after.documentDate)
                    .set(
                        DOCUMENT_INDEX.TRANSACTION_DATE,
                        requireNotNull(after.transactionDate) { "transactionDate is required" },
                    ).set(DOCUMENT_INDEX.CATEGORY_CODES, after.categoryCodes.sorted().toTypedArray())
                    .set(DOCUMENT_INDEX.STATE, after.state.name)
                    .set(DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID, after.acknowledgementId)
                    .set(DOCUMENT_INDEX.ERROR_CODE, after.errorCode)
                    .where(DOCUMENT_INDEX.TENANT_ID.eq(record.get(DOCUMENT_INDEX.TENANT_ID)))
                    .and(DOCUMENT_INDEX.DOC_UUID.eq(record.get(DOCUMENT_INDEX.DOC_UUID)))
                    .and(DOCUMENT_INDEX.VERSION.eq(record.get(DOCUMENT_INDEX.VERSION)))
                    .execute()
            changed += n
        }
        return changed
    }

    private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()

    private fun Record.toRow() =
        DocumentIndexRow(
            tenantId = req(DOCUMENT_INDEX.TENANT_ID).toString(),
            docUuid = req(DOCUMENT_INDEX.DOC_UUID).toString(),
            version = req(DOCUMENT_INDEX.VERSION),
            entityRef = req(DOCUMENT_INDEX.ENTITY_REF),
            documentNumber = get(DOCUMENT_INDEX.DOCUMENT_NUMBER),
            counterpartyName = get(DOCUMENT_INDEX.COUNTERPARTY_NAME),
            counterpartyUen = get(DOCUMENT_INDEX.COUNTERPARTY_UEN),
            amount = get(DOCUMENT_INDEX.AMOUNT),
            documentDate = get(DOCUMENT_INDEX.DOCUMENT_DATE),
            categoryCodes = req(DOCUMENT_INDEX.CATEGORY_CODES).filterNotNull().toSet(),
            state = IndexState.valueOf(req(DOCUMENT_INDEX.STATE)),
            acknowledgementId = get(DOCUMENT_INDEX.ACKNOWLEDGEMENT_ID),
            errorCode = get(DOCUMENT_INDEX.ERROR_CODE),
            transactionDate = get(DOCUMENT_INDEX.TRANSACTION_DATE),
        )

    private fun <T : Any> Record.req(field: Field<T?>): T = checkNotNull(get(field)) { "${field.name} is null" }
}
