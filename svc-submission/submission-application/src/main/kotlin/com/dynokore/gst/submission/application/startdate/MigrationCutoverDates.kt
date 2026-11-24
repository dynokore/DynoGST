package com.dynokore.gst.submission.application.startdate

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * The migration cutover date kept per tenant and entity from `EntityProfileChanged` (INV-REQ-1109, INV-T-28452):
 * event-carried state of tenancy-svc, read without a synchronous call.
 */
interface MigrationCutoverDates {
    /**
     * Stores [date] for ([tenant], [entityRef]) when no row exists or [effectiveDate] is later than the stored one.
     * Returns true when the stored row changed; an older or equal [effectiveDate] changes nothing.
     */
    fun record(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
        effectiveDate: LocalDate,
    ): Boolean

    /** The stored cutover date of the entity under [tenant] only, or null when none is stored. */
    fun find(
        tenant: TenantId,
        entityRef: String,
    ): LocalDate?
}
