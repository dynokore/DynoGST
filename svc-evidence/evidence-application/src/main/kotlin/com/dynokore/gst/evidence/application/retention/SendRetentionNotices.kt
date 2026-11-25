package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The outcome of one run: the partitions that got a notice and those that failed; the job fails when any failed. */
data class NoticeRun(
    val noticed: List<String>,
    val failed: List<String>,
)

/**
 * Chooses the window, the recipient and the message for each partition of a closed tenant that is past its retention
 * end. A tenant with no custody holder is not closed. It fails closed: a retention end that
 * cannot be read, a blank holder email or a failing port puts the partition into [NoticeRun.failed], and the other
 * partitions are still handled. The holder email is the only personal data in the notice (OWASP ASVS, data
 * protection).
 */
@Suppress("LongParameterList") // one port per fact the ruled design names
class SendRetentionNotices(
    private val candidates: NoticeCandidates,
    private val holders: CustodyHolderReader,
    private val window: NoticeWindowSetting,
    private val ends: RetentionEndReader,
    private val recorder: RetentionNoticeRecorder,
    private val clock: Clock,
) {
    private enum class Outcome { NOTICED, UNTOUCHED, FAILED }

    fun run(tenant: String): NoticeRun {
        val holder = holders.holder(tenant) ?: return NoticeRun(emptyList(), emptyList())
        val now = clock.instant()
        val outcomes = candidates.pending(tenant).map { it.partitionId to handle(tenant, holder, it, now) }

        fun idsWith(outcome: Outcome) = outcomes.filter { it.second == outcome }.map { it.first }

        return NoticeRun(noticed = idsWith(Outcome.NOTICED), failed = idsWith(Outcome.FAILED))
    }

    // Any failure of any port fails this partition only; the run result carries it to the job body.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun handle(
        tenant: String,
        holder: CustodyHolderReader.CustodyHolder,
        candidate: NoticeCandidate,
        now: Instant,
    ): Outcome =
        try {
            val end = ends.retentionEnd(tenant, candidate.partitionId)
            when {
                end == null -> Outcome.FAILED
                end.isAfter(now) -> Outcome.UNTOUCHED
                holder.email.isBlank() -> Outcome.FAILED
                recorder.record(tenant, notice(tenant, holder, candidate, now)) -> Outcome.NOTICED
                else -> Outcome.UNTOUCHED
            }
        } catch (e: Exception) {
            Outcome.FAILED
        }

    private fun notice(
        tenant: String,
        holder: CustodyHolderReader.CustodyHolder,
        candidate: NoticeCandidate,
        notifiedAt: Instant,
    ): RetentionNotice {
        // NoticeWindow refuses a setting that breaks its contract; the stored holder value only lengthens.
        val setting = NoticeWindow(window.days(tenant))
        val days = maxOf(setting.days, holder.noticeWindowDays ?: 0)
        return RetentionNotice(
            partitionId = candidate.partitionId,
            notifiedAt = notifiedAt,
            windowDays = days,
            recipients = listOf(holder.email),
            template = TEMPLATE,
            channel = CHANNEL,
            templateParameters =
                mapOf(
                    "periodStart" to candidate.periodFrom.toString(),
                    "periodEnd" to candidate.periodTo.toString(),
                    "noticeWindowEndsAt" to notifiedAt.plus(days.toLong(), ChronoUnit.DAYS).toString(),
                ),
        )
    }

    private companion object {
        const val TEMPLATE = "retention-notice"
        const val CHANNEL = "EMAIL"
    }
}
