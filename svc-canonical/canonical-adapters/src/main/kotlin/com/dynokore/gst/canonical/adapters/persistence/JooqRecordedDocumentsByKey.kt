package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SOURCE_EVENT
import com.dynokore.gst.canonical.application.netting.RecordedDocumentsByKey
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.RecordedDocument
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only, read-only, in the caller's open `canonical` transaction (no migration). Resolves source keys of
 * one tenant and source system to the recorded document each names: the Doc UUID from `source_event`, the entity from
 * `document`, the function from the latest `inbound_event` row of the document (the one the record path appended).
 * A key with no `source_event` row, a key that names two different Doc UUIDs (across revisions), a Doc UUID with no
 * `document` row and a document with no `inbound_event` row (its function is unknown) are absent from the answer: a
 * declaration that resolves to nothing declares nothing. An empty key list reads nothing.
 */
class JooqRecordedDocumentsByKey(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : RecordedDocumentsByKey {
    override fun lookup(
        tenantId: UUID,
        sourceSystem: String,
        keys: List<String>,
    ): Map<String, RecordedDocument> {
        check(tenants.current().value == tenantId) { "the lookup tenant is not the bound tenant" }
        if (keys.isEmpty()) return emptyMap()
        val named =
            dsl
                .selectDistinct(SOURCE_EVENT.SOURCE_KEY, SOURCE_EVENT.DOC_UUID)
                .from(SOURCE_EVENT)
                .where(SOURCE_EVENT.TENANT_ID.eq(tenantId))
                .and(SOURCE_EVENT.SOURCE_SYSTEM.eq(sourceSystem))
                .and(SOURCE_EVENT.SOURCE_KEY.`in`(keys))
                .fetch()
                .groupBy({ it[SOURCE_EVENT.SOURCE_KEY]!! }, { it[SOURCE_EVENT.DOC_UUID]!! })
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
        val docUuids = named.values.toSet()
        val entities =
            dsl
                .select(DOCUMENT.DOC_UUID, DOCUMENT.ENTITY_ID)
                .from(DOCUMENT)
                .where(DOCUMENT.TENANT_ID.eq(tenantId))
                .and(DOCUMENT.DOC_UUID.`in`(docUuids))
                .fetch()
                .associate { it[DOCUMENT.DOC_UUID]!! to it[DOCUMENT.ENTITY_ID]!! }
        val functions = latestFunctions(tenantId, docUuids)
        return named.entries
            .mapNotNull { (key, docUuid) ->
                val entity = entities[docUuid]
                val function = functions[docUuid]
                if (entity == null || function == null) null else key to RecordedDocument(docUuid, entity, function)
            }.toMap()
    }

    private fun latestFunctions(
        tenantId: UUID,
        docUuids: Set<UUID>,
    ): Map<UUID, DocumentFunction> {
        val latest = LinkedHashMap<UUID, DocumentFunction>()
        dsl
            .select(INBOUND_EVENT.DOC_UUID, INBOUND_EVENT.DOCUMENT_FUNCTION)
            .from(INBOUND_EVENT)
            .where(INBOUND_EVENT.TENANT_ID.eq(tenantId))
            .and(INBOUND_EVENT.DOC_UUID.`in`(docUuids))
            .orderBy(INBOUND_EVENT.VERSION.desc())
            .fetch()
            .forEach { row ->
                latest.putIfAbsent(
                    row[INBOUND_EVENT.DOC_UUID]!!,
                    DocumentFunction.valueOf(row[INBOUND_EVENT.DOCUMENT_FUNCTION]!!),
                )
            }
        return latest
    }
}
