package com.dynokore.gst.submission.application.compliance

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * One activation of an entity's mapping pack, as `MappingPackActivated` reports it (INV-AC-26739). [eventId] is the
 * id of the event that carried it; [packLabel] is the event's `packVersion`, the version label of the pack.
 * [effectiveTo] is null for an open-ended pack. [activatedBy] is the acting user id, never a role (INV-Q-10647).
 */
data class PackActivation(
    val eventId: String,
    val entityRef: String,
    val packId: String,
    val packLabel: String,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val activatedBy: String,
    val activatedAt: Instant,
)

/** The append-only history of mapping pack activations per entity (INV-REQ-980). */
fun interface PackActivations {
    /**
     * Stores [activation] for [tenant] in the open tenant transaction; false when that tenant already holds a row for
     * its event id, which then stays as it is.
     */
    fun record(
        tenant: TenantId,
        activation: PackActivation,
    ): Boolean
}
