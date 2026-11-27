package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.platform.outboxandqueue.OutboundEvent
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Maps a [PackEvent] to the contract's event type and payload (TPL-05). The payload is the `data` of the rules
 * asyncapi.yaml message, field for field; a test per channel validates it against the AsyncAPI schema.
 */
class PackEventMapper(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun map(event: PackEvent): OutboundEvent =
        when (event) {
            is PackEvent.RulePackStaged -> staged(event)
            is PackEvent.RulePackActivated -> activated(event)
            is PackEvent.PackCurrencyChanged -> currency(event)
            is PackEvent.UnreviewedActivationRecorded -> unreviewedActivation(event)
        }

    private fun staged(event: PackEvent.RulePackStaged): OutboundEvent =
        OutboundEvent(
            RulesEventCatalogue.RULE_PACK_STAGED,
            mapper.writeValueAsString(
                linkedMapOf(
                    "packIdentifier" to event.packIdentifier,
                    "packType" to event.packType,
                    "version" to event.version,
                    "effectiveFrom" to event.effectiveFrom.toString(),
                    "minEngineVersion" to event.minEngineVersion,
                    "artefact" to
                        linkedMapOf(
                            "manifest" to event.manifest,
                            "checksums" to event.checksums,
                            "signature" to event.signature,
                            "contents" to jsonValue(event.contents),
                        ),
                ),
            ),
        )

    /**
     * The one JSON value of [contents], numbers kept exact. Empty bytes, text that does not parse and trailing text
     * fail the mapping, so the import transaction stores no outbox row. The parser's exception quotes its input, so it
     * is neither a cause nor part of the message.
     */
    @Suppress("SwallowedException") // the parser's message quotes the contents
    private fun jsonValue(contents: ByteArray): JsonNode {
        val value =
            try {
                mapper
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(contents)
            } catch (e: JacksonException) {
                null
            }
        require(value != null && !value.isMissingNode) { "the pack contents are not exactly one JSON value" }
        return value
    }

    private fun activated(event: PackEvent.RulePackActivated): OutboundEvent =
        OutboundEvent(
            RulesEventCatalogue.RULE_PACK_ACTIVATED,
            mapper.writeValueAsString(
                linkedMapOf(
                    "packIdentifier" to event.packIdentifier,
                    "version" to event.version,
                    "effectiveFrom" to event.effectiveFrom.toString(),
                ),
            ),
        )

    private fun unreviewedActivation(event: PackEvent.UnreviewedActivationRecorded): OutboundEvent =
        OutboundEvent(
            RulesEventCatalogue.UNREVIEWED_ACTIVATION_RECORDED,
            mapper.writeValueAsString(
                linkedMapOf(
                    "tenantId" to event.tenantId,
                    "packType" to event.packType,
                    "packVersion" to event.version,
                    "effectiveFrom" to event.effectiveFrom.toString(),
                    "reviewed" to false,
                ),
            ),
        )

    private fun currency(event: PackEvent.PackCurrencyChanged): OutboundEvent =
        OutboundEvent(
            RulesEventCatalogue.PACK_CURRENCY_CHANGED,
            mapper.writeValueAsString(
                buildMap<String, Any> {
                    put("depCode", event.depCode)
                    put("status", if (event.raised) "RAISED" else "CLEARED")
                    event.packIdentifier?.let { put("packIdentifier", it) }
                    event.packVersion?.let { put("packVersion", it) }
                    event.daysToEffectiveDate?.let { put("daysToEffectiveDate", it) }
                    event.daysSinceLastReached?.let { put("daysSinceLastReached", it) }
                },
            ),
        )
}
