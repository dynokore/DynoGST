package com.dynokore.gst.tenancy.domain

import java.time.LocalDate

/** The stored lifecycle states of an entity (five-state CHECK of `lifecycle_state`; INV-Q-7491 A). */
enum class LifecycleState { DRAFT, LIVE, SUSPENDED, DEREGISTERED, RETIRED }

/** The profile attribute names the lifecycle events carry (opaque strings in the contract; rules-svc reads them). */
object LifecycleAttributes {
    const val LIFECYCLE_STATE = "lifecycleState"
    const val REGISTRATION_END_DATE = "registrationEndDate"
}

/** The inputs of the go-live gate (INV-REQ-987) as the use case read them. */
data class GoLiveFacts(
    /** Trigger classes neither mapped, waived nor covered by another solution; empty when coverage is complete. */
    val uncoveredTriggers: List<String>,
    /** The latest certification outcome, or null when the entity has no record. */
    val certification: CertificationOutcome?,
    val failedScenarios: List<String>,
    /** The names of the required elections not yet recorded. */
    val outstandingElections: List<String>,
    /** The document classes with no CURRENT rendition record (INV-AC-27909). */
    val missingRenditionClasses: List<String>,
    /** True when the rendition records could not be read: the item is unmet and named as unknown. */
    val renditionUnknown: Boolean,
    /** True when the coverage port could not answer: the item is unmet and named as unknown. */
    val coverageUnknown: Boolean = false,
    /** True only when the stored tenant state equals OPEN (INV-Q-8097 B); any other value is unmet. */
    val tenantOpen: Boolean = false,
)

/** The stored states of a tenant, in order (INV-REQ-8100, owner answer INV-Q-8097 B). */
enum class TenantState { SCHEDULED, OPEN, CLOSED }

/** The go-live gate: every unmet item is named (INV-REQ-987, INV-Q-16403). */
object GoLiveGate {
    const val COVERAGE_UNKNOWN = "trigger coverage unknown"
    const val TENANT_NOT_OPEN = "tenant not open"
    const val RENDITION_INCOMPLETE = "RENDITION_RESPONSIBILITY_INCOMPLETE"
    const val RENDITION_UNKNOWN = "RENDITION_RESPONSIBILITY_UNKNOWN"

    /** One text per unmet item, in the order coverage, certification, elections, rendition; empty when allowed. */
    fun unmet(facts: GoLiveFacts): List<String> =
        buildList {
            if (!facts.tenantOpen) add(TENANT_NOT_OPEN)
            if (facts.coverageUnknown) add(COVERAGE_UNKNOWN)
            if (facts.uncoveredTriggers.isNotEmpty()) {
                add("trigger coverage incomplete: ${facts.uncoveredTriggers.joinToString(", ")}")
            }
            if (facts.certification == null) add("sandbox certification has no record")
            if (facts.certification == CertificationOutcome.FAILED) {
                add("sandbox certification failed: ${facts.failedScenarios.joinToString(", ")}")
            }
            if (facts.outstandingElections.isNotEmpty()) {
                add("required elections outstanding: ${facts.outstandingElections.joinToString(", ")}")
            }
            addRendition(facts)
        }

    /** Every named class gives an item, in the order of [DocumentClasses.ALL]; unreadable records give one item. */
    private fun MutableList<String>.addRendition(facts: GoLiveFacts) {
        if (facts.renditionUnknown) {
            add(RENDITION_UNKNOWN)
            return
        }
        facts.missingRenditionClasses
            .sortedBy(
                DocumentClasses.ALL::indexOf,
            ).forEach { add("$RENDITION_INCOMPLETE: $it") }
    }
}

/** The rules of the lifecycle changes that need no port. */
object LifecycleRules {
    /** A repeated goLive on a live entity is a no-op (INV-REQ-963 b3). */
    fun goLiveIsRepeat(current: String): Boolean = current == LifecycleState.LIVE.name

    /** The unsubmitted block of deregistration (INV-REQ-956 b1): blocked unless an acknowledgement is recorded. */
    fun deregistrationBlocked(
        unsubmitted: Int,
        override: UnsubmittedOverride?,
    ): Boolean = unsubmitted > 0 && override == null

    /** The registration end must not be absent; the date is the ISO text the event carries. */
    fun isoDate(date: LocalDate): String = date.toString()
}
