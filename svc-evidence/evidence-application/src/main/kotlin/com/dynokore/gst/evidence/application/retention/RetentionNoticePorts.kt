package com.dynokore.gst.evidence.application.retention

import java.time.Instant
import java.time.LocalDate

/** A partition that has no retention notice yet. */
data class NoticeCandidate(
    val partitionId: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
)

interface NoticeCandidates {
    /** The partitions of the tenant in state ATTACHED or DETACHED that have no row in `retention_notice`. */
    fun pending(tenant: String): List<NoticeCandidate>
}

interface CustodyHolderReader {
    /**
     * The custody holder as stored for a notice. The email is not checked here: the use case reports a blank one as a
     * failure of each due partition. Personal data: [toString] shows no value.
     */
    data class CustodyHolder(
        val name: String,
        val email: String,
        val mobile: String?,
        val noticeWindowDays: Int?,
    ) {
        override fun toString(): String = "CustodyHolder(redacted)"
    }

    /** The custody holder of the tenant, or null when the tenant has no row. */
    fun holder(tenant: String): CustodyHolder?
}

interface NoticeWindowSetting {
    /** The resolved installation notice window of the tenant in days, always at least 1; throws when unreadable. */
    fun days(tenant: String): Int
}

/**
 * The notice of one partition: the facts of the notice row and the values the message needs. Personal data:
 * [toString] never shows the recipients.
 */
data class RetentionNotice(
    val partitionId: String,
    val notifiedAt: Instant,
    val windowDays: Int,
    val recipients: List<String>,
    val template: String,
    val channel: String,
    val templateParameters: Map<String, String>,
) {
    override fun toString(): String = "RetentionNotice(partitionId=$partitionId, recipients=redacted)"
}

interface RetentionNoticeRecorder {
    /** True when a notice was written, false when the partition already had one. */
    fun record(
        tenant: String,
        notice: RetentionNotice,
    ): Boolean
}
