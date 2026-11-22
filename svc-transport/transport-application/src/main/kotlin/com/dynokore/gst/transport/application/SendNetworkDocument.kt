package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import java.time.Clock

/** The entity has no AP connection record, so no adapter can be chosen: nothing is sent. */
class NoApConnectionRecord(
    entity: EntityRef,
) : IllegalStateException("entity $entity has no AP connection record")

/**
 * The network send port: one place a document is sent to a Peppol recipient through a named adapter. It never throws
 * for an AP answer; every failure it cannot classify is a [TransportOutcome.TransientFailure]. Implemented in the
 * adapters slice.
 */
interface NetworkSender {
    fun send(
        doc: DocRef,
        payload: ByteArray,
        recipient: String,
        adapter: AdapterId,
    ): TransportOutcome
}

/**
 * The sendNetworkDocument operation of the internal transport interface: payload bytes by value for a Doc UUID and a
 * version, sent once to the Peppol recipient through the adapter of the entity's connection record. The repeat key is
 * (Doc UUID, version): a repeat of a settled send returns the original outcome and sends nothing. A Doc UUID recorded
 * under another entity is refused (INV-Q-8172) and so is an entity with no connection record, both before any send.
 * The use case checks no role and asks no authorisation decision.
 */
class SendNetworkDocument(
    private val sender: NetworkSender,
    private val attempts: AttemptLog,
    private val connections: EntityAdapterAssignments,
    private val clock: Clock,
) {
    fun send(
        entity: EntityRef,
        doc: DocRef,
        payload: ByteArray,
        recipient: String,
    ): TransportOutcome {
        val recorded = attempts.entityOf(doc.docUuid)
        if (recorded != null && recorded != entity) throw DocUuidRecordedUnderOtherEntity(doc.docUuid)
        attempts.settledOutcome(doc)?.let { return it }
        val adapter = connections.adapterFor(entity) ?: throw NoApConnectionRecord(entity)
        val attempt = attempts.begin(entity, doc, adapter, clock.instant())
        val outcome =
            runCatching {
                sender.send(
                    doc,
                    payload,
                    recipient,
                    adapter,
                )
            }.getOrElse { TransportOutcome.TransientFailure }
        attempts.complete(attempt, outcome, clock.instant())
        return outcome
    }
}
