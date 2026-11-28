package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SANDBOX_CERTIFICATION
import com.dynokore.gst.tenancy.application.certification.CertificationHistory
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryEntry
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPage
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.gst.tenancy.application.certification.CertificationRecord
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The stored certification runs of an entity of the bound tenant (jOOQ only, CS-18), paged by keyset on
 * (recorded_at, seq). Every run is one entry. Forced row-level security shows only the bound tenant's rows.
 */
class JooqCertificationHistory(
    private val dsl: DSLContext,
) : CertificationHistory {
    override fun page(
        entityRef: String,
        limit: Int,
        after: CertificationHistoryPosition?,
    ): CertificationHistoryPage {
        val c = SANDBOX_CERTIFICATION
        val position =
            after?.let {
                DSL
                    .row(c.RECORDED_AT, c.SEQ)
                    .gt(DSL.row(OffsetDateTime.ofInstant(it.recordedAt, ZoneOffset.UTC), it.sequence))
            } ?: DSL.noCondition()
        val rows =
            dsl
                .select(c.SEQ, c.OUTCOME, c.SUITE_VERSION, c.FAILED_SCENARIOS, c.RECORDED_BY, c.RECORDED_AT)
                .from(c)
                .join(ENTITY)
                .on(ENTITY.TENANT_ID.eq(c.TENANT_ID).and(ENTITY.ID.eq(c.ENTITY_ID)))
                .where(ENTITY.REF.eq(entityRef).and(position))
                .orderBy(c.RECORDED_AT.asc(), c.SEQ.asc())
                .limit(limit + 1)
                .fetch()
                .map {
                    CertificationHistoryEntry(
                        it[c.SEQ].column("seq"),
                        CertificationRecord(
                            entityRef,
                            CertificationOutcome.valueOf(it[c.OUTCOME].column("outcome")),
                            it[c.SUITE_VERSION].column("suite_version"),
                            it[c.FAILED_SCENARIOS]?.filterNotNull(),
                            it[c.RECORDED_BY].column("recorded_by"),
                            it[c.RECORDED_AT].column("recorded_at").toInstant(),
                        ),
                    )
                }
        val entries = rows.take(limit)
        val last =
            if (rows.size > limit) {
                entries.last().let { CertificationHistoryPosition(it.record.recordedAt, it.sequence) }
            } else {
                null
            }
        return CertificationHistoryPage(entries, last)
    }
}
