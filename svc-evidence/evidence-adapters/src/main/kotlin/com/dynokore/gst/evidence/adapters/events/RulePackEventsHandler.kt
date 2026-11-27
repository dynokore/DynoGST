package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.cases.BreachTermsActivation
import com.dynokore.gst.evidence.application.cases.BreachTermsRefusal
import com.dynokore.gst.evidence.application.cases.BreachTermsReplica
import com.dynokore.gst.evidence.application.cases.BreachTermsStage
import com.dynokore.gst.evidence.application.cases.BreachTermsStaging
import com.dynokore.gst.evidence.application.cases.BreachTermsVersion
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The consumer of the platform `RulePackStaged` and `RulePackActivated` events for the breach terms replica
 * (INV-REQ-1385, INV-AC-29315). It handles exactly [STAGED_TYPE] and [ACTIVATED_TYPE]; any other type is a
 * [ContractViolation] before the payload is read. It runs in the inbox consumer's transaction, so a refusal leaves
 * nothing written. Messages name members and never values.
 *
 * A staged event is checked in the order PACK_CONTENTS_MISSING, PACK_NOT_PLATFORM, PACK_TYPE_CONFLICT,
 * PACK_CONTENTS_INVALID, PACK_VERSION_CONFLICT. Only the platform tenant's events write. An activation of a pack
 * identifier that is not recorded, or of a breach terms version that is not staged, throws [IllegalStateException],
 * which the inbox retries.
 */
class RulePackEventsHandler(
    private val replica: BreachTermsReplica,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome =
        when (event.type) {
            STAGED_TYPE -> staged(event)
            ACTIVATED_TYPE -> activated(event)
            else -> throw ContractViolation("the event type is not a platform rule pack event this consumer handles")
        }

    private fun staged(event: Envelope): HandleOutcome {
        val data = Payload.parse(event.dataJson)
        val contents =
            data.get("artefact")?.get("contents")?.takeUnless { it.isNull }
                ?: refuse(BreachTermsRefusal.PACK_CONTENTS_MISSING, "artefact.contents is absent or null")
        val identifier = Payload.text(data, "packIdentifier")
        val packType = Payload.text(data, "packType")
        val version = Payload.text(data, "version")
        return if (event.tenant.isPlatform) {
            stagedByPlatform(identifier, packType, version, contents, BreachTermsStaging(event.time, event.id.value))
        } else {
            stagedByTenant(identifier, packType)
        }
    }

    private fun stagedByPlatform(
        identifier: String,
        packType: String,
        version: String,
        contents: JsonNode,
        staging: BreachTermsStaging,
    ): HandleOutcome {
        if (replica.recordType(identifier, packType) != packType) refuseTypeConflict()
        if (packType != BREACH_TERMS_PACK_TYPE) return HandleOutcome.IGNORED
        return when (replica.stage(identifier, version, Contents.terms(contents), staging)) {
            BreachTermsStage.STORED -> {
                HandleOutcome.APPLIED
            }

            BreachTermsStage.UNCHANGED -> {
                HandleOutcome.IGNORED
            }

            BreachTermsStage.CONFLICT -> {
                refuse(BreachTermsRefusal.PACK_VERSION_CONFLICT, "artefact.contents differs for a stored version")
            }
        }
    }

    /** A staged event of a tenant other than the platform tenant writes nothing. */
    private fun stagedByTenant(
        identifier: String,
        packType: String,
    ): HandleOutcome {
        if (packType == BREACH_TERMS_PACK_TYPE) {
            refuse(BreachTermsRefusal.PACK_NOT_PLATFORM, "this packType is accepted from the platform tenant only")
        }
        val recorded = replica.typeOf(identifier)
        if (recorded != null && recorded != packType) refuseTypeConflict()
        return HandleOutcome.IGNORED
    }

    private fun activated(event: Envelope): HandleOutcome =
        if (event.tenant.isPlatform) activatedByPlatform(Payload.parse(event.dataJson)) else HandleOutcome.IGNORED

    private fun activatedByPlatform(data: JsonNode): HandleOutcome {
        val identifier = Payload.text(data, "packIdentifier")
        val version = Payload.text(data, "version")
        val effectiveFrom = Payload.date(data, "effectiveFrom")
        val packType = checkNotNull(replica.typeOf(identifier)) { "the packIdentifier is not staged yet" }
        if (packType != BREACH_TERMS_PACK_TYPE) return HandleOutcome.IGNORED
        return when (replica.activate(identifier, version, effectiveFrom)) {
            BreachTermsActivation.APPLIED -> HandleOutcome.APPLIED
            BreachTermsActivation.UNCHANGED -> HandleOutcome.IGNORED
            BreachTermsActivation.NOT_STAGED -> error("the version is not staged yet")
        }
    }

    private fun refuseTypeConflict(): Nothing =
        refuse(BreachTermsRefusal.PACK_TYPE_CONFLICT, "packType differs from the recorded type of packIdentifier")

    companion object {
        const val STAGED_TYPE = "com.dynokore.gst.platform.RulePackStaged.v1"
        const val ACTIVATED_TYPE = "com.dynokore.gst.platform.RulePackActivated.v1"

        /** The published wire value (breach-terms-pack-contents.schema.json), compared by equality. */
        const val BREACH_TERMS_PACK_TYPE = "breach-terms"
    }
}

private fun refuse(
    reason: BreachTermsRefusal,
    detail: String,
): Nothing = throw ContractViolation("${reason.name}: $detail")

/** Non-empty and without the NUL character, which a text column cannot hold. */
private fun storable(text: String): Boolean = text.isNotEmpty() && '\u0000' !in text

/** The breach terms of `artefact.contents`; members the contract schema does not name are ignored (API-41). */
private object Contents {
    private val maxHours = BigDecimal.valueOf(Long.MAX_VALUE / Duration.ofHours(1).seconds)

    fun terms(contents: JsonNode): BreachTermsVersion {
        if (!contents.isObject) invalid("artefact.contents is not an object")
        val sourceReference =
            contents
                .get("sourceReference")
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf { storable(it) }
                ?: invalid("sourceReference is not a non-empty text")
        return BreachTermsVersion(
            assessmentWithinHours = hours(contents, "assessmentWithinHours"),
            notificationWithinHours = hours(contents, "notificationWithinHours"),
            sourceReference = sourceReference,
        )
    }

    /**
     * A JSON number with no fraction part, at least 1 (JSON Schema `integer`). The contract sets no maximum; a figure
     * a [Duration] cannot hold is refused.
     */
    private fun hours(
        contents: JsonNode,
        name: String,
    ): Long =
        contents
            .get(name)
            ?.takeIf { it.isNumber }
            ?.decimalValue()
            ?.takeIf { it >= BigDecimal.ONE && it <= maxHours && it.stripTrailingZeros().scale() <= 0 }
            ?.longValueExact()
            ?: invalid("$name is not a whole number of hours of 1 or more that a duration can hold")

    private fun invalid(detail: String): Nothing = refuse(BreachTermsRefusal.PACK_CONTENTS_INVALID, detail)
}

/** The event payload; a number with a fraction keeps every digit, so no figure is rounded into a whole one. */
private object Payload {
    private val json =
        JsonMapper
            .builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build()

    fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (_: JacksonException) {
            // no cause: its message may repeat the payload
            throw ContractViolation("the payload is not JSON")
        }

    fun text(
        data: JsonNode,
        name: String,
    ): String =
        data
            .get(name)
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { storable(it) }
            ?: throw ContractViolation("the payload member $name is missing or not a non-empty text")

    fun date(
        data: JsonNode,
        name: String,
    ): LocalDate =
        try {
            LocalDate.parse(text(data, name))
        } catch (_: DateTimeParseException) {
            // no cause: its message repeats the value
            throw ContractViolation("the payload member $name is not a date")
        }
}
