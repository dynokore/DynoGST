package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.BULK_RESOLVE_OPERATION
import com.dynokore.gst.masterdata.application.counterparty.BulkResolveOperations
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-REQ-1010 behaviour 4. The operation ids of the bound tenant; the tenancy-jooq listeners add the tenant column.
 * The insert runs in the open transaction of the use case, so a failed write rolls the key back; two concurrent first
 * calls with one key are serialised by the primary key and the loser reads a repeat.
 */
class JooqBulkResolveOperations(
    private val dsl: DSLContext,
    private val clock: Clock,
) : BulkResolveOperations {
    override fun claim(
        operationId: UUID,
        actor: String,
    ): Boolean =
        dsl
            .insertInto(
                BULK_RESOLVE_OPERATION,
                BULK_RESOLVE_OPERATION.PRINCIPAL,
                BULK_RESOLVE_OPERATION.OPERATION_ID,
                BULK_RESOLVE_OPERATION.RECORDED_AT,
            ).values(actor, operationId, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
}
