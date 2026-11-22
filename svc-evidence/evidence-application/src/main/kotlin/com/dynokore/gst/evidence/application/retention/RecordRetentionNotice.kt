package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.configresolver.BusinessDate
import java.time.Instant

enum class RecordRetentionNoticeResult { RECORDED, NOT_FOUND }

/**
 * Records the retention notice of a partition with the VP-66 window in force on the business date of [notifiedAt], the
 * user ID of the acknowledgement that backs a shortened window and the sole-operator fact of that acknowledgement
 * (INV-REQ-2462, INV-REQ-994; INV-AC-26255). The inputs of the decision are stored with the notice, so a later change
 * of the installation value leaves the row as it was (NIST SP 800-53 AU-3). No role is stored (INV-Q-10647). An unknown
 * partition stores nothing.
 */
class RecordRetentionNotice(
    private val partitions: PartitionRegistry,
    private val source: NoticeWindowSource,
    private val writer: NoticeWriter,
) {
    fun execute(
        tenant: String,
        partitionId: String,
        notifiedAt: Instant,
    ): RecordRetentionNoticeResult {
        val partition = partitions.find(partitionId) ?: return RecordRetentionNoticeResult.NOT_FOUND
        val on = notifiedAt.atZone(BusinessDate.ZONE).toLocalDate()
        writer.record(
            tenant,
            partitionId,
            notifiedAt,
            source.window(tenant, on),
            source.acknowledgement(tenant, partition.entityRef, notifiedAt),
        )
        return RecordRetentionNoticeResult.RECORDED
    }
}
