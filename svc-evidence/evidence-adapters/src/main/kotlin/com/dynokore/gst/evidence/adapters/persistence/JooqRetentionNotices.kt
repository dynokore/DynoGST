package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.events.RetentionNoticeMessageOutbox
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionNotice.Companion.RETENTION_NOTICE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.RetentionPartition.Companion.RETENTION_PARTITION
import com.dynokore.gst.evidence.application.retention.NoticeWindowSource
import com.dynokore.gst.evidence.application.retention.RetentionNotice
import com.dynokore.gst.evidence.application.retention.RetentionNoticeRecorder
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * [RetentionNoticeRecorder] in the caller's tenant transaction at READ COMMITTED (INV-REQ-639, INV-AC-26996;
 * transactional outbox): the `retention_notice` row and the message outbox row. It catches nothing and takes no
 * savepoint, so any failure propagates and the caller's transaction rolls both writes back.
 *
 * Two runs cannot both find no notice: the first statement takes a transaction advisory lock on the tenant and the
 * partition. It needs no table privilege, so the runtime login keeps `select` only on the partition registry
 * (NIST SP 800-53 AC-6). The row stores the acknowledgement that backs a shortened window, the user ID and never a
 * role (INV-AC-26255, INV-Q-10647). A window shorter than the value in force on the date that acknowledgement is read
 * for is refused, since the two reads of the setting can differ across midnight.
 */
class JooqRetentionNotices(
    private val dsl: DSLContext,
    private val source: NoticeWindowSource,
    private val messages: RetentionNoticeMessageOutbox,
) : RetentionNoticeRecorder {
    override fun record(
        tenant: String,
        notice: RetentionNotice,
    ): Boolean {
        val id = UUID.fromString(tenant)
        lock(id, notice.partitionId)
        val entity = entityOf(id, notice.partitionId)
        if (noticed(id, notice.partitionId)) return false
        val on = notice.notifiedAt.atZone(BusinessDate.ZONE).toLocalDate()
        val inForce = source.window(tenant, on).days
        check(inForce <= notice.windowDays) {
            "the window of ${notice.windowDays} days is shorter than the $inForce days in force on $on"
        }
        val acknowledgement = source.acknowledgement(tenant, entity, notice.notifiedAt)
        val closes = notice.notifiedAt.plus(Duration.ofDays(notice.windowDays.toLong()))
        val n = RETENTION_NOTICE
        dsl
            .insertInto(n)
            .set(n.TENANT_ID, id)
            .set(n.PARTITION_ID, notice.partitionId)
            .set(n.NOTIFIED_AT, notice.notifiedAt.atOffset(ZoneOffset.UTC))
            .set(n.WINDOW_DAYS, notice.windowDays)
            .set(n.WINDOW_CLOSES_AT, closes.atOffset(ZoneOffset.UTC))
            .set(n.ACKNOWLEDGED_BY, acknowledgement?.userId)
            .set(n.ACKNOWLEDGED_SOLE_OPERATOR, acknowledgement?.soleOperator)
            .execute()
        messages.append(TenantId(id), notice, EntityRef(entity))
        return true
    }

    private fun lock(
        tenant: UUID,
        partitionId: String,
    ) {
        val key = DSL.`val`("retention-notice:$tenant:$partitionId")
        val hash =
            DSL.function(DSL.name("pg_catalog", "hashtextextended"), SQLDataType.BIGINT, key, DSL.`val`(0L))
        dsl.select(DSL.function(DSL.name("pg_catalog", "pg_advisory_xact_lock"), SQLDataType.OTHER, hash)).fetch()
    }

    /** The entity of the tenant's partition; the foreign key alone would accept another tenant's partition id. */
    private fun entityOf(
        tenant: UUID,
        partitionId: String,
    ): String {
        val p = RETENTION_PARTITION
        val row =
            dsl
                .select(p.ENTITY_REF)
                .from(p)
                .where(p.TENANT_ID.eq(tenant), p.PARTITION_ID.eq(partitionId))
                .fetchOne()
        return checkNotNull(row) { "the tenant has no partition $partitionId" }.value1().req()
    }

    private fun noticed(
        tenant: UUID,
        partitionId: String,
    ): Boolean {
        val n = RETENTION_NOTICE
        return dsl.fetchExists(
            dsl.selectOne().from(n).where(n.TENANT_ID.eq(tenant), n.PARTITION_ID.eq(partitionId)),
        )
    }
}
