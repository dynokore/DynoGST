package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.OffboardingCustodyHolder.Companion.OFFBOARDING_CUSTODY_HOLDER
import com.dynokore.gst.evidence.application.retention.CustodyHolder
import com.dynokore.gst.evidence.application.retention.CustodyHolderRecords
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** `offboarding_custody_holder` over the tenant-bound [DSLContext] (jOOQ only, STK §3; INV-REQ-639). */
class JooqCustodyHolderRecords(
    private val dsl: DSLContext,
) : CustodyHolderRecords {
    private val t = OFFBOARDING_CUSTODY_HOLDER

    override fun record(
        tenant: TenantId,
        offboardingRef: String,
        holder: CustodyHolder,
        noticeWindowDays: Int?,
        receivedAt: Instant,
    ) {
        dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenant.value)
            .set(t.OFFBOARDING_REF, offboardingRef)
            .set(t.CUSTODY_HOLDER_NAME, holder.name)
            .set(t.CUSTODY_HOLDER_EMAIL, holder.contactEmail)
            .set(t.CUSTODY_HOLDER_MOBILE, holder.contactMobile)
            .set(t.NOTICE_WINDOW_DAYS, noticeWindowDays)
            .set(t.RECEIVED_AT, OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
            .onConflict(t.TENANT_ID, t.OFFBOARDING_REF)
            .doNothing()
            .execute()
    }
}
