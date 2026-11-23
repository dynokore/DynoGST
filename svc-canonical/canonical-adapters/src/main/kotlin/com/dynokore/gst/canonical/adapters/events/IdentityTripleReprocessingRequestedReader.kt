package com.dynokore.gst.canonical.adapters.events

import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * What an `IdentityTripleReprocessingRequested` event asks (INV-REQ-1006). [constitution], [effectiveFrom] and
 * [changeRef] are carried as sent (null when absent); the handler uses only [entityRef] and [docUuids].
 */
data class IdentityTripleReprocessingRequest(
    val entityRef: String,
    val constitution: String?,
    val effectiveFrom: String?,
    val changeRef: String?,
    val docUuids: List<UUID>,
)

/**
 * Reads the data of the event. A payload that is not a JSON object, has no text `entityRef`, has no array `docUuids`,
 * or has a `docUuids` item that is not a UUID text (8-4-4-4-12 hexadecimal, as `format: uuid`) is rejected with
 * [ContractViolation] (dead letter at once), the rejection of `NetDerivationRequestedReader`. Members the contract
 * does not name are ignored.
 */
class IdentityTripleReprocessingRequestedReader {
    private val json = JsonMapper.builder().build()

    fun read(dataJson: String): IdentityTripleReprocessingRequest {
        val data = parse(dataJson)
        val entityRef = text(data, ENTITY_REF) ?: throw ContractViolation("$ENTITY_REF is missing or not text")
        val items =
            data.get(DOC_UUIDS)?.takeIf { it.isArray }
                ?: throw ContractViolation("$DOC_UUIDS is missing or not an array")
        return IdentityTripleReprocessingRequest(
            entityRef,
            text(data, CONSTITUTION),
            text(data, EFFECTIVE_FROM),
            text(data, CHANGE_REF),
            items.toList().map { uuid(it) },
        )
    }

    private fun text(
        node: JsonNode,
        member: String,
    ): String? = node.get(member)?.takeIf { it.isString && it.asString().isNotEmpty() }?.asString()

    private fun uuid(node: JsonNode): UUID {
        if (!node.isString || !UUID_TEXT.matches(node.asString())) {
            throw ContractViolation("$DOC_UUIDS holds an item that is not a UUID text")
        }
        return UUID.fromString(node.asString())
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val ENTITY_REF = "entityRef"
        const val CONSTITUTION = "constitution"
        const val EFFECTIVE_FROM = "effectiveFrom"
        const val CHANGE_REF = "changeRef"
        const val DOC_UUIDS = "docUuids"
        val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
