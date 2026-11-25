package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import java.time.Duration

/**
 * The job that sends the retention notices. Its schedule and times come from configuration; this code holds no default
 * for them (twelve-factor, config).
 */
object RetentionNoticeJob {
    const val NAME = "evidence-retention-notice"

    fun definition(
        schedule: JobSchedule,
        tolerance: Duration,
        lockAtMostFor: Duration,
    ): JobDefinition =
        JobDefinition(
            name = NAME,
            ownerService = "evidence-svc",
            entityScope = JobEntityScope.ENTITY,
            schedule = schedule,
            criticality = JobCriticality.STANDARD,
            tolerance = tolerance,
            lockAtMostFor = lockAtMostFor,
        )
}
