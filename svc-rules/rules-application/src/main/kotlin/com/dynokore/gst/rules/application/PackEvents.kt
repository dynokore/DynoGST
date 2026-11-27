package com.dynokore.gst.rules.application

import com.dynokore.platform.outboxandqueue.EventContext
import java.time.LocalDate

/**
 * The domain events rules-svc publishes (rules contract asyncapi.yaml; INV-REQ-815). Each is one catalogued type; an
 * uncatalogued type cannot be built here and the emission guard refuses one that is not catalogued for rules.
 */
sealed interface PackEvent {
    /** The pack staged after verification (INV-REQ-1196 behaviour 2). */
    data class RulePackStaged(
        val packIdentifier: String,
        val packType: String,
        val version: String,
        val effectiveFrom: LocalDate,
        val minEngineVersion: String,
        val manifest: Map<String, String>,
        val checksums: Map<String, String>,
        val signature: String,
        /** The bytes of the verified pack's `contents.json`, sent as `artefact.contents` (INV-AC-29312). */
        val contents: ByteArray,
    ) : PackEvent {
        override fun equals(other: Any?): Boolean =
            other is RulePackStaged &&
                packIdentifier == other.packIdentifier &&
                packType == other.packType &&
                version == other.version &&
                effectiveFrom == other.effectiveFrom &&
                minEngineVersion == other.minEngineVersion &&
                manifest == other.manifest &&
                checksums == other.checksums &&
                signature == other.signature &&
                contents.contentEquals(other.contents)

        override fun hashCode(): Int =
            listOf(packIdentifier, packType, version, signature, contents.contentHashCode()).hashCode()
    }

    data class RulePackActivated(
        val packIdentifier: String,
        val version: String,
        val effectiveFrom: LocalDate,
    ) : PackEvent

    data class PackCurrencyChanged(
        val depCode: String,
        val raised: Boolean,
        val packIdentifier: String? = null,
        val packVersion: String? = null,
        val daysToEffectiveDate: Int? = null,
        val daysSinceLastReached: Int? = null,
    ) : PackEvent
}

/**
 * Events leave only through the outbox in the command's transaction (ARC-05, INV-REQ-1211): the adapter appends to the
 * outbox of the open transaction and never publishes anywhere else.
 */
interface PackEventEmitter {
    fun emit(
        events: List<PackEvent>,
        context: EventContext,
    )
}
