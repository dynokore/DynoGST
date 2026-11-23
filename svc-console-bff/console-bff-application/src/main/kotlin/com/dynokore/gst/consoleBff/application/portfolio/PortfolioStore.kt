package com.dynokore.gst.consoleBff.application.portfolio

import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import java.time.Instant
import java.time.LocalDate

/** The period facts of INV-REQ-85 as tenancy returned them (INV-AC-26279); stored, never recomputed. */
data class StoredPeriod(
    val entityRef: String,
    val periodRef: String,
    val state: PeriodState,
    val filingDueDate: LocalDate,
    val filingDate: LocalDate?,
    val extensionGranted: Boolean,
)

/** The readiness fact of a period; [blockedCount] is set only for [Verdict.BLOCKED]. */
data class StoredReadiness(
    val entityRef: String,
    val periodRef: String,
    val verdict: Verdict,
    val blockedCount: Int?,
) {
    init {
        require((verdict == Verdict.BLOCKED) == (blockedCount != null)) { "blockedCount is set only for BLOCKED" }
        require(blockedCount == null || blockedCount >= 0) { "blockedCount is at least 0" }
    }
}

/** A stored period with its readiness fact, or null when none was stored. */
data class StoredFact(
    val period: StoredPeriod,
    val readiness: StoredReadiness?,
)

/** The portfolio read model (CQRS, event-fed), keyed (tenant, entity, period). */
interface PortfolioStore {
    /** Upserts the period facts by (tenant, entity, period). */
    fun putPeriods(
        tenantId: String,
        rows: List<StoredPeriod>,
    )

    /** Writes only when [observedAt] is later than the stored value; true when it wrote. */
    fun putReadiness(
        tenantId: String,
        row: StoredReadiness,
        observedAt: Instant,
    ): Boolean

    /** The stored periods of [entityRefs], each with its readiness or null; an entity not written has no row. */
    fun facts(
        tenantId: String,
        entityRefs: Set<String>,
    ): List<StoredFact>
}
