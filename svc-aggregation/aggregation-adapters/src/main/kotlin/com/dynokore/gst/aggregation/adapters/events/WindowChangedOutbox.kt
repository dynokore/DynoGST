package com.dynokore.gst.aggregation.adapters.events

import com.dynokore.gst.aggregation.application.MissedPart
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.application.WindowChangePublisher
import com.dynokore.gst.aggregation.application.WindowMissedPublisher
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The producer-owned counter of `AggregateWindowChanged` (`stateVersion`): starts at 1 for a key of tenant, entity,
 * prescribed period and window part and rises by one per call, in the transaction that is open.
 */
fun interface WindowStateVersions {
    fun next(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        from: LocalDate,
        to: LocalDate,
    ): Long
}

/**
 * The adapter of [WindowChangePublisher] (INV-REQ-413 item 2, channel `aggregationAggregateWindowChanged`): one
 * `AggregateWindowChanged` per part that moves to CLOSED or LATE, appended to the outbox in the transaction the caller
 * has open, which is the one of the close. The payload is `aggregate-window-changed-payload.schema.json` of
 * platform-contracts: `entityRef`, `prescribedPeriod` (the `periodRef` of the span), `window.from`, `window.to`,
 * `state`, `stateVersion` (the counter of [WindowStateVersions]) and, for CLOSED, `aggregateId`. A state OPEN is not
 * a change of an existing window and is refused.
 *
 * As the adapter of [WindowMissedPublisher] it appends one event per call with `state` MISSED and the `findingCode`
 * [FINDING_CODE], with a `stateVersion` from the same counter and key as the CLOSED and LATE events of the part. For a
 * missed window with no known span, `spanKnown` is false, `prescribedPeriod` is [SPAN_UNKNOWN_PERIOD] and the window
 * dates are those of the aggregation window.
 */
class WindowChangedOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val versions: WindowStateVersions,
    private val mapper: ObjectMapper = ObjectMapper(),
) : WindowChangePublisher,
    WindowMissedPublisher {
    override fun changed(change: WindowChange) {
        require(change.state == WindowState.CLOSED || change.state == WindowState.LATE) {
            "only a window part that moves to CLOSED or LATE is published"
        }
        val closed = change.state == WindowState.CLOSED
        val aggregateId = change.aggregateId
        require(!closed || aggregateId != null) { "a CLOSED part carries its aggregate id" }
        val stateVersion =
            versions.next(
                tenants.current(),
                change.window.entityRef,
                change.part.periodRef,
                change.part.start,
                change.part.end,
            )
        val payload =
            mapper.createObjectNode().also { node ->
                node.put("entityRef", change.window.entityRef)
                node.put("prescribedPeriod", change.part.periodRef)
                node.putObject("window").also {
                    it.put("from", change.part.start.toString())
                    it.put("to", change.part.end.toString())
                }
                node.put("state", change.state.name)
                node.put("stateVersion", stateVersion)
                if (closed) node.put("aggregateId", aggregateId.toString())
            }
        outbox.append(
            listOf(OutboundEvent(TYPE, mapper.writeValueAsString(payload))),
            context(change.window, change.part.start),
        )
    }

    override fun missed(item: MissedPart) {
        val part = item.part
        val periodRef = part?.periodRef ?: SPAN_UNKNOWN_PERIOD
        val from = part?.start ?: item.window.window.start
        val to = part?.end ?: item.window.window.end
        val stateVersion = versions.next(tenants.current(), item.window.entityRef, periodRef, from, to)
        val payload =
            mapper.createObjectNode().also { node ->
                node.put("entityRef", item.window.entityRef)
                node.put("prescribedPeriod", periodRef)
                node.putObject("window").also {
                    it.put("from", from.toString())
                    it.put("to", to.toString())
                }
                node.put("state", STATE_MISSED)
                node.put("findingCode", FINDING_CODE)
                if (part == null) node.put("spanKnown", false)
                node.put("stateVersion", stateVersion)
            }
        outbox.append(
            listOf(OutboundEvent(TYPE, mapper.writeValueAsString(payload))),
            context(item.window, from),
        )
    }

    private fun context(
        window: OpenWindow,
        from: LocalDate,
    ): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = tenants.current(),
            entity = EntityRef(window.entityRef),
            docUuid = null,
            transactionDate = null,
            aggregateKey = "window:${window.entityRef}:${window.aggregateType}:$from",
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }

    companion object {
        /** The type of `AggregationEvents` in the bootstrap (the catalogue entry of the AsyncAPI message). */
        const val TYPE = "com.dynokore.gst.aggregation.AggregateWindowChanged.v1"

        /** The `prescribedPeriod` of a missed window with no known span (`spanKnown` false). */
        const val SPAN_UNKNOWN_PERIOD = "UNKNOWN-SPAN"

        /** "Aggregation window not closed" of `catalogue/errors/error-codes.yaml` of platform-contracts. */
        const val FINDING_CODE = "ING-027"

        /** A state of the event only: a missed part has no persisted [WindowState]. */
        private const val STATE_MISSED = "MISSED"
    }
}
