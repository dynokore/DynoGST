package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/**
 * INV-REQ-1010 behaviour 4: records the operation id of a bulk resolution. The store keys the pair
 * (tenant, actor, operation id) and writes it in the transaction of the effect.
 */
interface BulkResolveOperations {
    /** True when the pair is recorded now; false when it was recorded before. [actor] is the acting user id. */
    fun claim(
        operationId: UUID,
        actor: String,
    ): Boolean
}

/** The counts a bulk resolution reports: [resolved] documents handled now, [unchanged] documents of a repeat. */
data class BulkResolveOutcome(
    val resolved: Int,
    val unchanged: Int,
)

/**
 * INV-REQ-1010 behaviour 4: a bulk resolution is safe to repeat. The first run of an operation id records the write
 * of [record]; a repeat of the same id by the same actor writes nothing and reports every distinct document as
 * unchanged.
 */
class BulkResolve(
    private val tx: TransactionRunner,
    private val record: RecordCounterparty,
    private val operations: BulkResolveOperations,
) {
    fun handle(
        operationId: UUID,
        actor: String,
        command: RecordCommand,
    ): BulkResolveOutcome =
        tx.inTransaction {
            val documents = command.docUuids.distinct().size
            if (operations.claim(operationId, actor)) {
                record.handle(command)
                BulkResolveOutcome(resolved = documents, unchanged = 0)
            } else {
                BulkResolveOutcome(resolved = 0, unchanged = documents)
            }
        }
}
