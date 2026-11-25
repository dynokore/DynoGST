package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.gst.canonical.application.exposure.ExposureWindowStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Condition
import org.jooq.DSLContext
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
            .fetch { row ->
                checkNotNull(row.get(DOCUMENT.ENTITY_REF)) {
                    "a version in the exposure window has no entity_ref stored"
                }
            }.toSet()

    private fun matching(window: ExposureWindow): Condition =
        DSL.and(
            DOCUMENT_VERSION.TENANT_ID.eq(tenants.current().value),
            DOCUMENT_VERSION.CREATED_BY.eq(window.principalRef),
            DOCUMENT_VERSION.CREATED_AT.ge(window.recordedFrom.atOffset(ZoneOffset.UTC)),
            DOCUMENT_VERSION.CREATED_AT.lt(window.recordedTo.atOffset(ZoneOffset.UTC)),
        )
}
