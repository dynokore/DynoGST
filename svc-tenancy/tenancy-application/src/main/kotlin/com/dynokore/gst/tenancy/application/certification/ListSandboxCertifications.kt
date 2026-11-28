package com.dynokore.gst.tenancy.application.certification

import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.domain.SandboxCertificationPermissions
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant

/** One stored run with its [sequence], at least 1 and unique in the entity. */
data class CertificationHistoryEntry(
    val sequence: Long,
    val record: CertificationRecord,
)

/** The place of one run in the total order: recorded time ascending, then sequence ascending. */
data class CertificationHistoryPosition(
    val recordedAt: Instant,
    val sequence: Long,
)

/** One page of runs; [last] is the position to read after, or null on the last page. */
data class CertificationHistoryPage(
    val entries: List<CertificationHistoryEntry>,
    val last: CertificationHistoryPosition?,
)

/** The stored runs of the bound tenant's entities, read only (adapter: the boundary slice). */
fun interface CertificationHistory {
    /** At most [limit] runs of the entity that follow [after] in the total order; from the start when it is null. */
    fun page(
        entityRef: String,
        limit: Int,
        after: CertificationHistoryPosition?,
    ): CertificationHistoryPage
}

/** The outcome of [ListSandboxCertifications.list]. */
sealed interface ListCertificationsOutcome {
    data class Page(
        val page: CertificationHistoryPage,
    ) : ListCertificationsOutcome

    /** The caller does not hold `sandbox-certification.record` on the entity. */
    data class Forbidden(
        val reason: String,
    ) : ListCertificationsOutcome

    data object NotFound : ListCertificationsOutcome

    data class Invalid(
        val reason: String,
    ) : ListCertificationsOutcome
}

/**
 * listSandboxCertifications (INV-REQ-1008): every run recorded for an entity, a page at a time. The permission is
 * checked first, per request and fail closed ([PeriodAccess]); the stores are read only afterwards. Nothing is written.
 */
class ListSandboxCertifications(
    private val tx: TransactionRunner,
    private val access: PeriodAccess,
    private val entities: CertifiableEntities,
    private val history: CertificationHistory,
) {
    fun list(
        entityRef: String,
        limit: Int,
        after: CertificationHistoryPosition?,
    ): ListCertificationsOutcome {
        access.denial(entityRef, SandboxCertificationPermissions.RECORD)?.let {
            return ListCertificationsOutcome.Forbidden(it)
        }
        return if (limit !in MIN_LIMIT..MAX_LIMIT) {
            ListCertificationsOutcome.Invalid("limit must be between $MIN_LIMIT and $MAX_LIMIT")
        } else {
            tx.inTransaction {
                if (entities.exists(entityRef)) {
                    ListCertificationsOutcome.Page(history.page(entityRef, limit, after))
                } else {
                    ListCertificationsOutcome.NotFound
                }
            }
        }
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 500
    }
}
