package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.adapters.persistence.JooqReadinessReplayReader
import com.dynokore.gst.submission.adapters.persistence.StoredReadinessSignal
import com.dynokore.gst.submission.application.readiness.ReadinessChangedFacts
import com.dynokore.gst.submission.application.readiness.ReadinessChangedPublication
import com.dynokore.gst.submission.application.readiness.ReadinessSnapshot
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.ReplayPage
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper

/**
 * The producer side of the replay of ReadinessChanged (INV-REQ-1205 behavior 4, INV-T-29143): the stored readiness
 * signal of each entity and period of the bound tenant, as the snapshot the live publication would carry. The data is
 * built by [ReadinessChangedPublication.data] (no second serialiser); the aggregate key is `entityRef/periodRef` as the
 * live publication's; no `docSequence`. It reads `readiness_signal` only, never the outbox or the event log.
 */
class ReadinessReplaySource(
    private val reader: JooqReadinessReplayReader,
    private val mapper: ObjectMapper,
) : ReplayStateSource {
    override val eventType: String = ReadinessChangedPublication.EVENT_TYPE

    override fun tenants(): List<TenantId> = reader.tenants()

    override fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
    ): ReplayPage {
        require(limit >= 1) { "a page holds at least one state" }
        val rows = reader.page(scope, cursor?.let(::decode), limit + 1)
        val items = rows.take(limit)
        val next = if (rows.size > limit) encode(items.last()) else null
        return ReplayPage(items.map(::state), next)
    }

    private fun state(row: StoredReadinessSignal): ReplayedState {
        val counts =
            mapper.readTree(row.countsByStateJson).properties().associate {
                LifecycleState.valueOf(it.key) to it.value.asInt()
            }
        val facts =
            ReadinessChangedFacts(
                entityRef = row.entityRef,
                periodRef = row.periodRef,
                snapshot = ReadinessSnapshot(CheckVerdict.valueOf(row.verdict), row.blockedCount, counts),
                checkId = REPLAY_CHECK,
            )
        return ReplayedState(
            type = eventType,
            dataJson = ReadinessChangedPublication.data(facts),
            entity = EntityRef(row.entityRef),
            aggregateKey = "${row.entityRef}/${row.periodRef}",
            docSequence = null,
        )
    }

    /** The cursor is the key of the last state: the entity ref's length, a colon, the entity ref, the period ref. */
    private fun encode(row: StoredReadinessSignal) = "${row.entityRef.length}:${row.entityRef}${row.periodRef}"

    private fun decode(cursor: String): Pair<String, String> {
        val length = requireNotNull(cursor.substringBefore(':').toIntOrNull()) { "unknown replay cursor" }
        val body = cursor.substringAfter(':')
        require(length in 0..body.length) { "unknown replay cursor" }
        return body.substring(0, length) to body.substring(length)
    }

    private companion object {
        const val REPLAY_CHECK = "replay"
    }
}
