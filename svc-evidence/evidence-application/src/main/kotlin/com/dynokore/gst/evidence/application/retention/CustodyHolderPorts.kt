package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The retention custody holder named by an offboarding (INV-REQ-639, INV-REQ-956). Personal data: [toString] never
 * shows the contact values, so no log line or exception text can leak them.
 */
data class CustodyHolder(
    val name: String,
    val contactEmail: String,
    val contactMobile: String?,
) {
    init {
        require(name.isNotBlank()) { "the custody holder name is blank" }
        require(contactEmail.isNotBlank()) { "the custody holder email is blank" }
    }

    override fun toString(): String = "CustodyHolder(redacted)"
}

/** The store of the custody holder and notice window of an offboarding; one row per tenant and offboarding. */
interface CustodyHolderRecords {
    /** Stores the row once: a second call for the same tenant and offboarding changes nothing and raises nothing. */
    fun record(
        tenant: TenantId,
        offboardingRef: String,
        holder: CustodyHolder,
        noticeWindowDays: Int?,
        receivedAt: Instant,
    )
}
