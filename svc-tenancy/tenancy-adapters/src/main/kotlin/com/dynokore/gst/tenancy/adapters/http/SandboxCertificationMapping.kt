package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.certification.CertificationHistoryEntry
import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.gst.tenancy.application.certification.ListCertificationsOutcome
import com.dynokore.gst.tenancy.contract.model.SandboxCertificationListResponse
import com.dynokore.gst.tenancy.contract.model.SandboxCertificationRecord
import java.time.ZoneOffset

/**
 * The outcome of the history use case as the contract publishes it, in the order of the use case. `failedScenarios` is
 * absent when the stored value is null, and `nextCursor` is absent on the last page (API-14).
 */
internal fun certificationListOf(
    outcome: ListCertificationsOutcome,
    nextCursor: (CertificationHistoryPosition) -> String,
): SandboxCertificationListResponse =
    when (outcome) {
        is ListCertificationsOutcome.Page -> {
            SandboxCertificationListResponse(
                outcome.page.entries.map(::certificationRecordOf),
                outcome.page.last?.let(nextCursor),
            )
        }

        ListCertificationsOutcome.NotFound -> {
            notFound()
        }

        is ListCertificationsOutcome.Forbidden -> {
            forbidden(outcome.reason)
        }

        is ListCertificationsOutcome.Invalid -> {
            invalid(outcome.reason)
        }
    }

/** `recordedBy` is the stored acting user ID, never a role (INV-Q-10647). */
private fun certificationRecordOf(entry: CertificationHistoryEntry) =
    SandboxCertificationRecord(
        suiteVersion = entry.record.suiteVersion,
        outcome = SandboxCertificationRecord.Outcome.valueOf(entry.record.outcome.name),
        recordedBy = entry.record.recordedBy,
        recordedAt = entry.record.recordedAt.atOffset(ZoneOffset.UTC),
        sequence = entry.sequence,
        failedScenarios = entry.record.failedScenarios,
    )
