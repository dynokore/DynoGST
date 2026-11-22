package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.domain.breakglass.SignInBackoff
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** The `action` of the ACC-002 alert raised at the failure counts of [SignInBackoff.isAlertCount]. */
const val SIGN_IN_FAILED_ACTION = "SIGN_IN_FAILED"

sealed interface SignInWithBackoffOutcome {
    data class SignedIn(
        val session: BreakGlassSession,
    ) : SignInWithBackoffOutcome

    data object NotFound : SignInWithBackoffOutcome

    /** No open unlock: the session is resealed or its expiry has passed (409). */
    data object NotOpen : SignInWithBackoffOutcome

    /** A wrong sealing credential, or a tenant with no account row (403). */
    data object WrongCredential : SignInWithBackoffOutcome

    /** The back-off is running: the credential was not checked and nothing changed (429, Retry-After). */
    data class Waiting(
        val retryAfterSeconds: Long,
    ) : SignInWithBackoffOutcome
}

/**
 * `signInBreakGlass` with back-off (INV-REQ-896, INV-Q-9107 A, INV-AC-9184). The unlock must exist and be open; that is
 * decided before the counter. While the back-off runs every sign-in answers [SignInWithBackoffOutcome.Waiting] without
 * a credential check or a change of the back-off. Otherwise the credential is checked and the next state saved either way; the failure
 * counts of [SignInBackoff.isAlertCount] raise the ACC-002 alert. Every outcome writes one audit entry (action
 * `signInBreakGlass`, detail the outcome name).
 * [handle]'s `actor` is the caller's user ID (INV-Q-16511 A).
 */
@Suppress("LongParameterList") // one collaborator per port
class SignInWithBackoffUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val keeper: SealedAccountKeeper,
    private val attempts: SignInAttempts,
    private val effects: BreakGlassEffects,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        unlockRef: String,
        sealingCredential: String,
    ): SignInWithBackoffOutcome =
        tx.inTransaction {
            val session = sessions.find(unlockRef)
            if (session == null) {
                // the supplied reference is never written: a caller cannot choose a string for the log
                audited(actor, UNKNOWN_REF, OUTCOME_NOT_FOUND)
                return@inTransaction SignInWithBackoffOutcome.NotFound
            }
            val now = clock.instant()
            if (!session.isOpenAt(now)) {
                audited(actor, unlockRef, OUTCOME_NOT_OPEN)
                return@inTransaction SignInWithBackoffOutcome.NotOpen
            }
            val state = attempts.readForUpdate(unlockRef)
            if (state.isWaitingAt(now)) {
                audited(actor, unlockRef, OUTCOME_WAITING)
                return@inTransaction SignInWithBackoffOutcome.Waiting(state.remainingSecondsAt(now))
            }
            val right = keeper.matches(sealingCredential)
            val next = state.after(right, now)
            attempts.save(unlockRef, next)
            if (right) {
                audited(actor, unlockRef, OUTCOME_SIGNED_IN)
                SignInWithBackoffOutcome.SignedIn(session)
            } else {
                if (SignInBackoff.isAlertCount(next.failures)) {
                    // the alert call is the one entry of this call
                    effects.record(actor, SIGN_IN_OPERATION, unlockRef, SIGN_IN_FAILED_ACTION, OUTCOME_WRONG_CREDENTIAL)
                } else {
                    audited(actor, unlockRef, OUTCOME_WRONG_CREDENTIAL)
                }
                SignInWithBackoffOutcome.WrongCredential
            }
        }

    private fun audited(
        actor: String,
        ref: String,
        outcome: String,
    ) = effects.recordAudit(actor, SIGN_IN_OPERATION, ref, outcome)

    private companion object {
        const val SIGN_IN_OPERATION = "signInBreakGlass"
        const val UNKNOWN_REF = "unknown"
        const val OUTCOME_SIGNED_IN = "SIGNED_IN"
        const val OUTCOME_NOT_FOUND = "NOT_FOUND"
        const val OUTCOME_NOT_OPEN = "NOT_OPEN"
        const val OUTCOME_WRONG_CREDENTIAL = "WRONG_CREDENTIAL"
        const val OUTCOME_WAITING = "WAITING"
    }
}
