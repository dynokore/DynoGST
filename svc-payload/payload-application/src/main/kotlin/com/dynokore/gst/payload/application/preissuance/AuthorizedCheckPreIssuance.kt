package com.dynokore.gst.payload.application.preissuance

import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import java.time.LocalDate

/** The kind of the verified caller of the pre-issuance check. */
enum class PreIssuanceCallerKind { HUMAN, MACHINE }

/**
 * The verified caller: the verified [Principal] itself, passed through and never rebuilt from parts (OWASP ASVS V4,
 * NIST SP 800-53 AC-3). The recorded user id is [Principal.id]; the permission kind is derived from [Principal.kind]
 * (a CONSENT_AGENT is decided with the person key, INV-REQ-957).
 */
data class PreIssuanceCaller(
    val principal: Principal,
) {
    val id: String get() = principal.id
    val kind: PreIssuanceCallerKind
        get() =
            if (principal.kind ==
                PrincipalKind.MACHINE
            ) {
                PreIssuanceCallerKind.MACHINE
            } else {
                PreIssuanceCallerKind.HUMAN
            }
}

/** The port that decides one permission key for one caller on one entity, per request. */
fun interface PreIssuanceAuthority {
    fun holds(
        caller: PreIssuanceCaller,
        permission: String,
        entityRef: String,
    ): Boolean
}

/** The result of the authorised pre-issuance check. */
sealed interface AuthorizedPreIssuance {
    data class Checked(
        val check: PreIssuanceCheck,
    ) : AuthorizedPreIssuance

    data object Forbidden : AuthorizedPreIssuance
}

/**
 * INV-REQ-957: the decision of the existing permission key of the caller kind is taken once per request, before any
 * register lookup; a refusal or an unavailable decision (the authority throws) is Forbidden (fail closed, OWASP ASVS
 * V4, NIST SP 800-53 AC-3). No role name is checked.
 */
class AuthorizedCheckPreIssuance(
    private val delegate: CheckPreIssuance,
    private val authority: PreIssuanceAuthority,
) {
    @Suppress("LongParameterList")
    fun execute(
        caller: PreIssuanceCaller,
        supplierGstn: String,
        entityRef: String,
        currency: String,
        invoiceDate: LocalDate,
        submissionType: String?,
        gstAmount: String?,
    ): AuthorizedPreIssuance {
        val permission =
            when (caller.kind) {
                PreIssuanceCallerKind.HUMAN -> PERSON_PERMISSION
                PreIssuanceCallerKind.MACHINE -> MACHINE_PERMISSION
            }
        val held =
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                authority.holds(caller, permission, entityRef)
            } catch (e: Exception) {
                false
            }
        if (!held) return AuthorizedPreIssuance.Forbidden
        return AuthorizedPreIssuance.Checked(
            delegate.execute(supplierGstn, entityRef, currency, invoiceDate, submissionType, gstAmount),
        )
    }

    companion object {
        const val PERSON_PERMISSION = "transaction.ingest"
        const val MACHINE_PERMISSION = "machine.ingestion.channels.write"
    }
}
