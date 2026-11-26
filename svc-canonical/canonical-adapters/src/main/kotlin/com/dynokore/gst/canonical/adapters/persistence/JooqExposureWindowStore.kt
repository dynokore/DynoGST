package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SOURCE_EVENT
import com.dynokore.gst.canonical.application.exposure.ExposedVersion
import com.dynokore.gst.canonical.application.exposure.ExposurePosition
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.gst.canonical.application.exposure.ExposureWindowStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.ZoneOffset

/**
 * TPL-11. jOOQ only, read-only. The DSLContext is the one of the open `canonical` transaction; the tenant condition is
 * explicit and forced row-level security stays behind it. No predicate excludes a row without an entityRef: such a row
 * is read and fails the whole answer.
 */
class JooqExposureWindowStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ExposureWindowStore {
    /** One statement, so the missing-entityRef check and the answer see the same rows. */
    override fun entityRefs(window: ExposureWindow): Set<String> =
        dsl
            .selectDistinct(DOCUMENT.ENTITY_REF)
            .from(DOCUMENT_VERSION)
            .join(DOCUMENT)
            .on(DOCUMENT.DOC_UUID.eq(DOCUMENT_VERSION.DOC_UUID))
            .and(DOCUMENT.PARTITION_DATE.eq(DOCUMENT_VERSION.PARTITION_DATE))
            .and(DOCUMENT.ENTITY_ID.eq(DOCUMENT_VERSION.ENTITY_ID))
            .and(DOCUMENT.TENANT_ID.eq(DOCUMENT_VERSION.TENANT_ID))
            .where(matching(window))
            .fetch { row -> stored(row.get(DOCUMENT.ENTITY_REF)) }
            .toSet()

    /**
     * One statement, so the missing-entityRef check and the page see the same rows. A matching row without an
     * entityRef is selected whatever the permitted set and the position are, and sorts first, so any limit of at least
     * 1 reads it. The source fields come from the `source_event` row that the stored link of the version names.
     */
    override fun page(
        window: ExposureWindow,
        permittedEntityRefs: Set<String>,
        limit: Int,
        after: ExposurePosition?,
    ): List<ExposedVersion> {
        require(limit >= 1) { "limit is less than 1" }
        if (permittedEntityRefs.isEmpty()) return emptyList()
        val permitted = DOCUMENT.ENTITY_REF.eq(DSL.any(DSL.value(permittedEntityRefs.toTypedArray<String?>())))
        return dsl
            .select(
                DOCUMENT.ENTITY_REF,
                SOURCE_EVENT.SOURCE_SYSTEM,
                SOURCE_EVENT.SOURCE_KEY,
                SOURCE_EVENT.SOURCE_REVISION,
                DOCUMENT_VERSION.DOC_UUID,
                DOCUMENT_VERSION.VERSION,
                DOCUMENT_VERSION.CREATED_AT,
            ).from(DOCUMENT_VERSION)
            .join(DOCUMENT)
            .on(DOCUMENT.DOC_UUID.eq(DOCUMENT_VERSION.DOC_UUID))
            .and(DOCUMENT.PARTITION_DATE.eq(DOCUMENT_VERSION.PARTITION_DATE))
            .and(DOCUMENT.ENTITY_ID.eq(DOCUMENT_VERSION.ENTITY_ID))
            .and(DOCUMENT.TENANT_ID.eq(DOCUMENT_VERSION.TENANT_ID))
            .join(SOURCE_EVENT)
            .on(SOURCE_EVENT.TENANT_ID.eq(DOCUMENT_VERSION.TENANT_ID))
            .and(SOURCE_EVENT.SOURCE_SYSTEM.eq(DOCUMENT_VERSION.SOURCE_SYSTEM))
            .and(SOURCE_EVENT.SOURCE_KEY.eq(DOCUMENT_VERSION.SOURCE_KEY))
            .and(SOURCE_EVENT.SOURCE_REVISION.eq(DOCUMENT_VERSION.SOURCE_REVISION))
            .where(matching(window))
            .and(DOCUMENT.ENTITY_REF.isNull.or(permitted.and(following(after))))
            .orderBy(
                DOCUMENT.ENTITY_REF.isNull.desc(),
                SOURCE_EVENT.SOURCE_SYSTEM.asc(),
                DOCUMENT_VERSION.CREATED_AT.asc(),
                DOCUMENT_VERSION.DOC_UUID.asc(),
                DOCUMENT_VERSION.VERSION.asc(),
            ).limit(limit)
            .fetch(::exposedVersion)
    }

    /** Keyset condition over the listing order, so a page neither drops nor repeats a version. */
    private fun following(after: ExposurePosition?): Condition =
        if (after == null) {
            DSL.noCondition()
        } else {
            DSL
                .row(
                    SOURCE_EVENT.SOURCE_SYSTEM,
                    DOCUMENT_VERSION.CREATED_AT,
                    DOCUMENT_VERSION.DOC_UUID,
                    DOCUMENT_VERSION.VERSION,
                ).gt(after.sourceSystem, after.recordedAt.atOffset(ZoneOffset.UTC), after.docUuid, after.version)
        }

    private fun exposedVersion(row: Record): ExposedVersion =
        ExposedVersion(
            entityRef = stored(row.get(DOCUMENT.ENTITY_REF)),
            sourceSystem = checkNotNull(row.get(SOURCE_EVENT.SOURCE_SYSTEM)),
            sourceKey = checkNotNull(row.get(SOURCE_EVENT.SOURCE_KEY)),
            sourceRevision = checkNotNull(row.get(SOURCE_EVENT.SOURCE_REVISION)),
            docUuid = checkNotNull(row.get(DOCUMENT_VERSION.DOC_UUID)),
            version = checkNotNull(row.get(DOCUMENT_VERSION.VERSION)),
            recordedAt = checkNotNull(row.get(DOCUMENT_VERSION.CREATED_AT)).toInstant(),
        )

    private fun stored(entityRef: String?): String =
        checkNotNull(entityRef) { "a version in the exposure window has no entity_ref stored" }

    private fun matching(window: ExposureWindow): Condition =
        DSL.and(
            DOCUMENT_VERSION.TENANT_ID.eq(tenants.current().value),
            DOCUMENT_VERSION.CREATED_BY.eq(window.principalRef),
            DOCUMENT_VERSION.CREATED_AT.ge(window.recordedFrom.atOffset(ZoneOffset.UTC)),
            DOCUMENT_VERSION.CREATED_AT.lt(window.recordedTo.atOffset(ZoneOffset.UTC)),
        )
}
