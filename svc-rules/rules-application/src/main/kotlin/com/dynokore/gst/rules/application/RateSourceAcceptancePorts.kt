package com.dynokore.gst.rules.application

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** One scoped, expiring acceptance of one scope-pack rate source for one tenant (INV-REQ-504). */
data class RateSourceAcceptance(
    val tenant: TenantId,
    val sourceId: String,
    val rationale: String,
    val expiresOn: LocalDate,
    /** The authenticated user ID of the accepting call, never a role (INV-Q-10647). */
    val acceptedBy: String,
)

/** Holds the acceptances of rate sources. */
interface RateSourceAcceptanceStore {
    /** Writes the acceptance; replaces the row of the same tenant and source (the repeat rule of the contract). */
    fun replace(acceptance: RateSourceAcceptance)

    /** The acceptances of the tenant. */
    fun byTenant(tenant: TenantId): List<RateSourceAcceptance>
}

/** The ids the scope pack in force on [asOf] lists as INFERRED; null when it cannot answer (fail closed). */
fun interface InferredRateSourcePort {
    fun inferred(
        tenant: TenantId,
        asOf: LocalDate,
    ): Set<String>?
}

data class AcceptRateSourceCommand(
    val tenant: TenantId,
    /** The authenticated user ID of the call. */
    val actor: String,
    val sourceId: String,
    val rationale: String,
    val expiresOn: LocalDate,
)

sealed interface AcceptRateSourceResult {
    data class Accepted(
        val acceptance: RateSourceAcceptance,
    ) : AcceptRateSourceResult

    /** The actor does not hold `mapping-pack.approve` at tenant scope; carries the user ID, never a role. */
    data class NotPermitted(
        val actor: String,
    ) : AcceptRateSourceResult

    /**
     * Refused, nothing stored: [RefusalReason.INPUT_INVALID] for a rationale or expiry, [RefusalReason.UNKNOWN_ENTRY]
     * for a source that is not INFERRED in the scope pack in force, [RefusalReason.RATE_SOURCE_UNAVAILABLE] when the
     * scope pack cannot answer.
     */
    data class Refused(
        val reason: RefusalReason,
        val actor: String,
    ) : AcceptRateSourceResult
}

/**
 * Records the acceptance of one INFERRED rate source for one tenant (INV-REQ-504). The permission is a tenant-scope
 * key decided per request (OWASP ASVS V4, INV-Q-10027); every check runs before the one write.
 */
class AcceptRateSource(
    private val authority: PackActionAuthority,
    private val sources: InferredRateSourcePort,
    private val store: RateSourceAcceptanceStore,
    private val clock: Clock,
) {
    fun accept(command: AcceptRateSourceCommand): AcceptRateSourceResult {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val permitted = authority.holdsTenant(command.actor, command.tenant, MappingPackPermissions.APPROVE)
        val reason = if (permitted) refusalOf(command, today) else null
        return when {
            !permitted -> AcceptRateSourceResult.NotPermitted(command.actor)
            reason != null -> AcceptRateSourceResult.Refused(reason, command.actor)
            else -> record(command)
        }
    }

    private fun record(command: AcceptRateSourceCommand): AcceptRateSourceResult {
        val acceptance =
            RateSourceAcceptance(
                command.tenant,
                command.sourceId,
                command.rationale,
                command.expiresOn,
                command.actor,
            )
        store.replace(acceptance)
        return AcceptRateSourceResult.Accepted(acceptance)
    }

    /** The first failing check, in order: the input, then the scope pack; null when every check passes. */
    private fun refusalOf(
        command: AcceptRateSourceCommand,
        today: LocalDate,
    ): RefusalReason? {
        val valid =
            command.rationale.isNotBlank() &&
                command.rationale.length <= MAX_RATIONALE &&
                command.expiresOn.isAfter(today)
        val inferred = if (valid) sources.inferred(command.tenant, today) else emptySet()
        return when {
            !valid -> RefusalReason.INPUT_INVALID
            inferred == null -> RefusalReason.RATE_SOURCE_UNAVAILABLE
            command.sourceId !in inferred -> RefusalReason.UNKNOWN_ENTRY
            else -> null
        }
    }

    private companion object {
        const val MAX_RATIONALE = 500
    }
}
