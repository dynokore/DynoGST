package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * The consumer of `gst.payload.payload-rejected.v1`, the third record source (INV-REQ-3650 b1): appends the optional
 * `decisions` member of the event, as sent and wrapped as `{"decisions": ...}`, to the chain of the locator of
 * (docUuid, version) as a DECISION_TRACE record, or keeps it pending until the locator arrives. An event without
 * `decisions` has nothing to record and is applied. It runs in the inbox consumer's transaction. A record topic has
 * no `docsequence` check.
 *
 * A payload that is not JSON, lacks `docUuid` or `version`, or has a `decisions` that is not a non-empty array is
 * refused with [ContractViolation]: a poison record that goes to the dead letter and appends nothing.
 */
class PayloadRejectedHandler(
    private val appender: RecordAppender,
    private val clock: Clock = Clock.systemUTC(),
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val docUuid = uuid(data)
        val version = version(data)
        val decisions = data.get("decisions") ?: return HandleOutcome.APPLIED
        if (!decisions.isArray || decisions.isEmpty) {
            throw ContractViolation("decisions is not a non-empty array")
        }
        appender.appendBound(
            tenant = event.tenant,
            kind = RecordKind.DECISION_TRACE,
            docUuid = docUuid,
            version = version,
            eventId = event.id.value,
            content = CanonicalJson.bytes(json.createObjectNode().set("decisions", decisions)),
            arrivedAt = clock.instant(),
        )
        return HandleOutcome.APPLIED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun uuid(data: JsonNode): UUID =
        runCatching { UUID.fromString(data.get("docUuid")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("docUuid is missing or not a UUID", it) }

    private fun version(data: JsonNode): Int =
        data.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("version is missing or not a positive integer")
}
