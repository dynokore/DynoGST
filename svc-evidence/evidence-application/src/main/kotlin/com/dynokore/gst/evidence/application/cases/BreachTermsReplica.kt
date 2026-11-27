package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.BreachTerms
import java.time.LocalDate

/** Why a staged breach terms event is refused. A refusal message starts with the reason name and a colon. */
enum class BreachTermsRefusal {
    PACK_CONTENTS_MISSING,
    PACK_CONTENTS_INVALID,
    PACK_VERSION_CONFLICT,
    PACK_TYPE_CONFLICT,
    PACK_NOT_PLATFORM,
}

/** The terms of one staged pack version, as the published contents carry them. No value has a default. */
data class BreachTermsVersion(
    val assessmentWithinHours: Long,
    val notificationWithinHours: Long,
    val sourceReference: String,
)

/** What [BreachTermsReplica.stage] did with a staged version. */
enum class BreachTermsStage { STORED, UNCHANGED, CONFLICT }

/** What [BreachTermsReplica.activate] did with an activation. */
enum class BreachTermsActivation { APPLIED, UNCHANGED, NOT_STAGED }

/**
 * The local replica of the breach terms packs (INV-REQ-1385, INV-AC-29315; event-carried state transfer). The packs
 * are operator-maintained, so every row is a row of the platform tenant. Every write joins the caller's transaction.
 */
interface BreachTermsReplica {
    /**
     * Records [packType] as the type of [packIdentifier] only when none is recorded, and answers the recorded type. A
     * recorded type is never changed.
     */
    fun recordType(
        packIdentifier: String,
        packType: String,
    ): String

    /** The recorded type of [packIdentifier], or null when none is recorded. Writes nothing. */
    fun typeOf(packIdentifier: String): String?

    /** Stores a version once. A stored version is never changed: other terms are [BreachTermsStage.CONFLICT]. */
    fun stage(
        packIdentifier: String,
        version: String,
        terms: BreachTermsVersion,
    ): BreachTermsStage

    /**
     * Activates a staged version from [effectiveFrom]. [BreachTermsActivation.UNCHANGED] when it is already the
     * version in force for exactly that date; a version that is not staged is never invented.
     */
    fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): BreachTermsActivation

    /**
     * The terms of the activated version with the latest effective date on or before [on], the later applied
     * activation winning a tie; null when there is none.
     */
    fun activeTerms(on: LocalDate): BreachTerms?
}
