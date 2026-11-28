package com.dynokore.gst.tenancy.adapters.lifecycle

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The audit entries of the lifecycle changes (INV-REQ-963, NIST SP 800-53 AU-2 and AU-3), written through audit-log in
 * the request's transaction (INV-REQ-1226): the actor is the user who acted, never a role (INV-Q-10647). A suspension
 * stores the reason as given; a go-live names the second confirmer.
 */
class AuditLogLifecycleAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) {
    fun wentLive(
        actor: String,
        entityRef: String,
        confirmedBy: String,
    ) {
        record(AuditEntry(actor = actor, action = WENT_LIVE, scope = entityRef, reason = CONFIRMED_BY + confirmedBy))
    }

    fun suspended(
        actor: String,
        entityRef: String,
        reason: String,
    ) {
        record(AuditEntry(actor = actor, action = SUSPENDED, scope = entityRef, reason = reason))
    }

    private fun record(entry: AuditEntry) {
        val entityRef = entry.scope
        writer.record(entry, EventContexts.forAggregate(tenants.current(), EntityRef(entityRef), entityRef))
    }

    companion object {
        const val SUSPENDED = "entity.suspended"
        const val WENT_LIVE = "entity.went-live"
        private const val CONFIRMED_BY = "go-live confirmed by "
    }
}
