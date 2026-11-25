package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.OffboardingCustodyHolder.Companion.OFFBOARDING_CUSTODY_HOLDER
import com.dynokore.gst.evidence.application.retention.CustodyHolderReader
import org.jooq.DSLContext
import java.util.UUID

/**
 * [CustodyHolderReader] over `offboarding_custody_holder` on the caller's tenant-bound [DSLContext] (jOOQ only; row
 * level security applies; INV-REQ-639, INV-AC-26996): the row with the greatest `received_at`. Two rows that share
 * the greatest instant give null: no recipient is guessed (fail closed). A tenant text that is not a UUID reads
 * nothing.
 */
class JooqCustodyHolderReader(
    private val dsl: DSLContext,
) : CustodyHolderReader {
    override fun holder(tenant: String): CustodyHolderReader.CustodyHolder? {
        val id = runCatching { UUID.fromString(tenant) }.getOrNull() ?: return null
        val t = OFFBOARDING_CUSTODY_HOLDER
        val newest =
            dsl
                .selectFrom(t)
                .where(t.TENANT_ID.eq(id))
                .orderBy(t.RECEIVED_AT.desc())
                .limit(2)
                .fetch()
        val first = newest.firstOrNull()
        val tied = newest.size > 1 && newest[0].receivedAt.req().isEqual(newest[1].receivedAt.req())
        return first?.takeUnless { tied }?.let {
            CustodyHolderReader.CustodyHolder(
                it.custodyHolderName.req(),
                it.custodyHolderEmail.req(),
                it.custodyHolderMobile,
                it.noticeWindowDays,
            )
        }
    }
}
