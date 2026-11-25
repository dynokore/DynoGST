package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionNotice.Companion.RETENTION_NOTICE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartition.Companion.RETENTION_PARTITION
import com.dynokore.gst.evidence.application.retention.NoticeCandidate
import com.dynokore.gst.evidence.application.retention.NoticeCandidates
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.UUID

/**
 * [NoticeCandidates] over `retention_partition` and `retention_notice` on the caller's tenant-bound [DSLContext]
 * (jOOQ only; row level security applies; INV-REQ-639, INV-AC-26996). A period that cannot be dated keeps its
 * partition in the list with the widest range, as [JooqRetentionRecords.find] reads it: its retention end is then
 * unknown, so the use case reports it as a failure and it never drops out unseen. A tenant text that is not a UUID
 * reads nothing.
 */
class JooqNoticeCandidates(
    private val dsl: DSLContext,
) : NoticeCandidates {
    override fun pending(tenant: String): List<NoticeCandidate> {
        val id = runCatching { UUID.fromString(tenant) }.getOrNull() ?: return emptyList()
        val p = RETENTION_PARTITION
        val n = RETENTION_NOTICE
        return dsl
            .select(p.PARTITION_ID, p.PERIOD_FROM)
            .from(p)
            .where(
                p.TENANT_ID.eq(id),
                p.STATE.`in`(OPEN_STATES),
                DSL.notExists(
                    DSL
                        .selectOne()
                        .from(n)
                        .where(n.TENANT_ID.eq(p.TENANT_ID), n.PARTITION_ID.eq(p.PARTITION_ID)),
                ),
            ).orderBy(p.PARTITION_ID)
            .fetch()
            .map {
                val range = PeriodText.range(it.value2().req())
                NoticeCandidate(it.value1().req(), range?.first ?: LocalDate.MIN, range?.second ?: LocalDate.MAX)
            }
    }

    private companion object {
        val OPEN_STATES = listOf("ATTACHED", "DETACHED")
    }
}
