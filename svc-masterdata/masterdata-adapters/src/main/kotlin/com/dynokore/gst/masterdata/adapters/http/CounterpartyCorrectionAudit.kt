package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.CorrectableField
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.UUID

/**
 * One audit entry per applied privacy correction (INV-T-27119, INV-REQ-886), through audit-log in the transaction of
 * the appended version. The actor is the acting user ID (never a role, INV-Q-10647); the reason names the case id and
 * the field name and holds neither the old nor the new value.
 */
class CounterpartyCorrectionAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) {
    fun corrected(
        actor: String,
        entity: String,
        counterpartyRef: String,
        caseId: UUID,
        field: CorrectableField,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        writer.record(
            AuditEntry(
                actor = actor,
                action = ACTION,
                scope = entity,
                reason = "privacy case $caseId: field ${field.name} corrected",
            ),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = counterpartyRef,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        const val ACTION = "counterparty.corrected"
    }
}
