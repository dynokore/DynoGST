package com.dynokore.gst.submission.application.startdate

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/**
 * The members of an `EntityProfileChanged` event the consumer reads. [obligationStartDate] and
 * [migrationCutoverDate] are null when no changed attribute is named exactly `obligationStartDate` or
 * `migrationCutoverDate`.
 */
data class EntityProfileChangedData(
    val entityRef: String,
    val obligationStartDate: LocalDate?,
    val migrationCutoverDate: LocalDate?,
    val effectiveDate: LocalDate,
)

/** Reads the event data JSON of an `EntityProfileChanged` (the JSON library stays in the adapters, ARC-03). */
fun interface EntityProfileChangedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): EntityProfileChangedData
}

/**
 * Keeps the obligation start date (INV-REQ-335, INV-T-22201) and the migration cutover date (INV-REQ-1109,
 * INV-T-28452) of an entity from `EntityProfileChanged`. Each is recorded independently; the latest `effectiveDate`
 * wins, an older or equal one is not applied, and an event with neither attribute is IGNORED. A malformed event is a
 * [ContractViolation] and writes nothing. No message carries a field value.
 */
class EntityProfileChangedHandler(
    private val tenants: TenantContext,
    private val reader: EntityProfileChangedDataReader,
    private val startDates: ObligationStartDates,
    private val cutoverDates: MigrationCutoverDates,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val tenant = tenants.current()
        val startApplied =
            data.obligationStartDate?.let { startDates.record(tenant, data.entityRef, it, data.effectiveDate) } ?: false
        val cutoverApplied =
            data.migrationCutoverDate?.let { cutoverDates.record(tenant, data.entityRef, it, data.effectiveDate) }
                ?: false
        return if (startApplied || cutoverApplied) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
