package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.InferredRateSourcePort
import com.dynokore.gst.rules.application.RateSourceAcceptanceStore
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** The contents of the SCOPE pack in force for [tenant] on [asOf], or null when none is in force or it is not held. */
fun interface ScopePackInForce {
    fun contents(
        tenant: TenantId,
        asOf: LocalDate,
    ): ByteArray?
}

/**
 * The rate-source block of INV-REQ-504 for one tenant (INV-Q-14651 A, INV-Q-16533 A): the ids of the sources that an
 * entity of the tenant has elected (attribute `exchangeRateSource` in force on `asOf`, INV-REQ-1285) and that the scope
 * pack in force lists as INFERRED. A tenant with no election gets the empty set. A source is left out while the tenant
 * holds an acceptance of exactly that id whose `expiresOn` is after `asOf` (INV-T-24951); the acceptance of a mapping
 * pack entry is not one. Null when the answer cannot be given: the replica is not synced, no scope pack is in force,
 * its contents do not decode, or the acceptances cannot be read (fail closed, never the shorter list).
 * A retired node and a node of another kind are ignored, as svc-aggregation's StructureCodeLookup ignores a
 * retired one. [inferred] answers from the same scope pack read and depends on no election and no acceptance.
 */
class ReplicaRateSourcePort(
    private val structure: StructureReplica,
    private val acceptances: RateSourceAcceptanceStore,
    private val scopePack: ScopePackInForce,
) : RateSourcePort,
    InferredRateSourcePort {
    override fun blocking(
        tenant: TenantId,
        asOf: LocalDate,
    ): List<String>? {
        val inferred = inferredInOrder(tenant, asOf)
        val accepted = inferred?.let { acceptedOn(tenant, asOf) }
        if (inferred == null || accepted == null) return null
        val elected = electedBy(tenant, asOf)
        return inferred.filter { it in elected && it !in accepted }
    }

    override fun inferred(
        tenant: TenantId,
        asOf: LocalDate,
    ): Set<String>? = inferredInOrder(tenant, asOf)?.toSet()

    /** The ids the scope pack in force lists as INFERRED, in pack order; null when it cannot answer. */
    private fun inferredInOrder(
        tenant: TenantId,
        asOf: LocalDate,
    ): List<String>? =
        (if (structure.isSynced()) scopePack.contents(tenant, asOf) else null)
            ?.let(ScopePackRateSources::decode)
            ?.filter { (_, sourceClass) -> sourceClass == INFERRED }
            ?.map { it.first }
            ?.distinct()

    /** The ids the tenant's unexpired acceptances name; null when the acceptances cannot be read. */
    private fun acceptedOn(
        tenant: TenantId,
        asOf: LocalDate,
    ): Set<String>? =
        runCatching { acceptances.byTenant(tenant) }
            .onFailure { LOG.warn("the rate source acceptances cannot be read", it) }
            .getOrNull()
            ?.filter { it.tenant == tenant && it.expiresOn.isAfter(asOf) }
            ?.map { it.sourceId }
            ?.toSet()

    private fun electedBy(
        tenant: TenantId,
        asOf: LocalDate,
    ): Set<String> =
        structure.replica
            .heldWhere { it.tenant == tenant }
            .values
            .filter { it.kind == StructureNodeKind.ENTITY && !it.retired }
            .mapNotNull { it.attribute(ATTRIBUTE, asOf) }
            .toSet()

    companion object {
        const val ATTRIBUTE = "exchangeRateSource"
        private const val INFERRED = "INFERRED"
        private val LOG = LoggerFactory.getLogger(ReplicaRateSourcePort::class.java)
    }
}
