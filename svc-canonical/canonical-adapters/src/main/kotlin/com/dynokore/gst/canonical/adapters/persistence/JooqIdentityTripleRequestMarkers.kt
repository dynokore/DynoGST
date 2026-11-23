package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.IDENTITY_TRIPLE_REQUEST_MARKER
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleRequestMarkers
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-11. jOOQ only, in the caller's open `canonical` transaction bound to the tenant: the marker commits or rolls
 * back with the versions the handler writes. The primary key is (tenant, CloudEvents id).
 */
class JooqIdentityTripleRequestMarkers(
    private val dsl: DSLContext,
    private val clock: Clock,
) : IdentityTripleRequestMarkers {
    override fun markIfNew(
        tenantId: UUID,
        eventId: String,
    ): Boolean =
        dsl
            .insertInto(IDENTITY_TRIPLE_REQUEST_MARKER)
            .set(IDENTITY_TRIPLE_REQUEST_MARKER.TENANT_ID, tenantId)
            .set(IDENTITY_TRIPLE_REQUEST_MARKER.EVENT_ID, eventId)
            .set(IDENTITY_TRIPLE_REQUEST_MARKER.ANSWERED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
}
