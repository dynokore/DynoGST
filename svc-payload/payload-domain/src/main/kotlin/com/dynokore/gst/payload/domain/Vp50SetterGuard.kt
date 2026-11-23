package com.dynokore.gst.payload.domain

import java.time.Instant

/** The record of the AP's documented wrongful GST charge check for one Access Point connection (INV-REQ-1311). */
data class ApCheckRecord(
    val connectionRef: String,
    val actorUserId: String,
    val recordedAt: Instant,
)

enum class Vp50SetRefusal {
    NO_AP_CHECK_RECORD,
    RECORD_CONNECTION_MISMATCH,
    RECORD_WITHOUT_ACTOR,
    ACKNOWLEDGEMENT_NOT_IN_FORCE,
}

sealed interface Vp50SetOutcome {
    object Accepted : Vp50SetOutcome

    data class Refused(
        val reason: Vp50SetRefusal,
    ) : Vp50SetOutcome
}

/**
 * Setter guard of VP-50 at connection scope (INV-REQ-1311 behavior 4), pure and fail-closed. Going back to the
 * System's check needs neither record nor acknowledgement; the AP's check needs both.
 */
object Vp50SetterGuard {
    fun decide(
        option: WrongfulChargeCheck,
        connectionRef: String,
        record: ApCheckRecord?,
        acknowledgementInForce: Boolean,
    ): Vp50SetOutcome =
        when {
            option == WrongfulChargeCheck.SYSTEM_EVERY_PATH -> Vp50SetOutcome.Accepted
            record == null -> Vp50SetOutcome.Refused(Vp50SetRefusal.NO_AP_CHECK_RECORD)
            record.connectionRef != connectionRef -> Vp50SetOutcome.Refused(Vp50SetRefusal.RECORD_CONNECTION_MISMATCH)
            record.actorUserId.isBlank() -> Vp50SetOutcome.Refused(Vp50SetRefusal.RECORD_WITHOUT_ACTOR)
            !acknowledgementInForce -> Vp50SetOutcome.Refused(Vp50SetRefusal.ACKNOWLEDGEMENT_NOT_IN_FORCE)
            else -> Vp50SetOutcome.Accepted
        }
}
