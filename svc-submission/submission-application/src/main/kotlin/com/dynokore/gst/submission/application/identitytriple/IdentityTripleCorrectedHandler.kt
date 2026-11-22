package com.dynokore.gst.submission.application.identitytriple

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The members of an `IdentityTripleCorrected` event the consumer reads (INV-REQ-1006). */
data class IdentityTripleCorrectedData(
    val entityRef: String,
    val constitution: String,
    val effectiveFrom: LocalDate,
    val changeRef: String,
    val accessPointEscalation: Boolean,
)

/** Reads the event data JSON of an `IdentityTripleCorrected` (the JSON library stays in the adapters, ARC-03). */
fun interface IdentityTripleCorrectedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): IdentityTripleCorrectedData
}

/** The corrections received, one per (tenant, entity, constitution, change reference). */
interface IdentityTripleCorrections {
    /** Stores the correction; true only when a row was stored, false when the key was already stored. */
    fun record(
        tenant: TenantId,
        data: IdentityTripleCorrectedData,
    ): Boolean

    /** The stored correction of the bound tenant for the key, or null. */
    fun find(
        tenant: TenantId,
        entityRef: String,
        constitution: String,
        changeRef: String,
    ): IdentityTripleCorrectedData?
}

/**
 * Keeps the correction of `IdentityTripleCorrected` as a local fact (INV-REQ-1006, INV-T-26838): an idempotent
 * receiver, the first event of a key is APPLIED and a repeat is IGNORED. A malformed event is a [ContractViolation]
 * and writes nothing. No message carries a member value.
 */
class IdentityTripleCorrectedHandler(
    private val tenants: TenantContext,
    private val reader: IdentityTripleCorrectedDataReader,
    private val corrections: IdentityTripleCorrections,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        return if (corrections.record(tenants.current(), data)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
