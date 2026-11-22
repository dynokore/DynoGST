package com.dynokore.gst.consoleBff.application.privacy

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Bounded attempts of the evidence annotation, all with the identical key. */
const val ANNOTATION_ATTEMPTS = 3

/** Fixed application namespace of the version 5 Idempotency-Keys (RFC 9562). */
val PRIVACY_KEY_NAMESPACE: UUID = UUID.fromString("5b0f3c52-8a1e-4f0d-9c57-2d6e41a7b8c3")

val CORRECTABLE_FIELDS: Set<String> = setOf("legalName", "addressLine1", "addressLine2", "postalCode")

private const val CASE_OPEN = "OPEN"
private const val VERSION_MASK = 0x0f
private const val VERSION_5 = 0x50
private const val VARIANT_MASK = 0x3f
private const val VARIANT_RFC = 0x80
private const val UUID_BYTES = 16
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8

data class CorrectPersonalDataCommand(
    val tenantId: String,
    val principalId: String,
    val entityRef: String?,
    val counterpartyRef: String?,
    val ackId: String?,
    val caseId: String,
    val field: String,
    val correctedValue: String,
    val reason: String,
)

sealed interface CorrectPersonalDataOutcome {
    data class Corrected(
        val caseId: String,
        val field: String,
        val correctedValue: String,
        val correctedAt: Instant,
    ) : CorrectPersonalDataOutcome

    data object Invalid : CorrectPersonalDataOutcome

    data object Forbidden : CorrectPersonalDataOutcome

    data object NotFound : CorrectPersonalDataOutcome

    data class Relayed(
        val refusal: OwnerRefusal,
    ) : CorrectPersonalDataOutcome

    data object Unavailable : CorrectPersonalDataOutcome
}

private sealed interface Step<out T> {
    data class Go<out T>(
        val value: T,
    ) : Step<T>

    data class Stop(
        val outcome: CorrectPersonalDataOutcome,
    ) : Step<Nothing>
}

private fun <T> OwnerReply<T>.step(): Step<T> =
    when (this) {
        is OwnerReply.Ok -> Step.Go(value)
        is OwnerReply.Refused -> Step.Stop(CorrectPersonalDataOutcome.Relayed(refusal))
        is OwnerReply.Unavailable -> Step.Stop(CorrectPersonalDataOutcome.Unavailable)
    }

private data class Refs(
    val entityRef: String,
    val counterpartyRef: String,
    val ackId: String,
)

class CorrectPersonalData(
    private val authority: PrivacyAuthority,
    private val cases: PrivacyCases,
    private val corrections: CounterpartyCorrections,
    private val annotations: EvidenceAnnotations,
) {
    fun handle(command: CorrectPersonalDataCommand): CorrectPersonalDataOutcome {
        val refs = refs(command) ?: return CorrectPersonalDataOutcome.Invalid
        return if (authority.holds(command.tenantId, command.principalId, refs.entityRef)) {
            readCase(command, refs)
        } else {
            CorrectPersonalDataOutcome.Forbidden
        }
    }

    private fun refs(command: CorrectPersonalDataCommand): Refs? {
        val entityRef = command.entityRef
        val counterpartyRef = command.counterpartyRef
        val ackId = command.ackId
        val present = !entityRef.isNullOrBlank() && !counterpartyRef.isNullOrBlank() && !ackId.isNullOrBlank()
        return if (present && command.field in CORRECTABLE_FIELDS) Refs(entityRef, counterpartyRef, ackId) else null
    }

    private fun readCase(
        command: CorrectPersonalDataCommand,
        refs: Refs,
    ): CorrectPersonalDataOutcome =
        when (val status = cases.status(command.caseId).step()) {
            is Step.Stop -> {
                status.outcome
            }

            is Step.Go -> {
                if (status.value == CASE_OPEN) correct(command, refs) else CorrectPersonalDataOutcome.NotFound
            }
        }

    private fun correct(
        command: CorrectPersonalDataCommand,
        refs: Refs,
    ): CorrectPersonalDataOutcome {
        val request =
            CorrectionRequest(refs.entityRef, command.caseId, command.field, command.correctedValue, command.reason)
        val key = key("masterdata", command, refs)
        return when (val version = corrections.correct(refs.counterpartyRef, key, request).step()) {
            is Step.Stop -> version.outcome
            is Step.Go -> annotate(command, refs, version.value)
        }
    }

    private fun annotate(
        command: CorrectPersonalDataCommand,
        refs: Refs,
        version: CorrectedVersion,
    ): CorrectPersonalDataOutcome {
        val key = key("evidence", command, refs)
        val landed =
            (1..ANNOTATION_ATTEMPTS)
                .asSequence()
                .map { annotations.append(command.caseId, key, refs.ackId, command.field, command.reason) }
                .firstOrNull { it !is OwnerReply.Unavailable }
        return when (val step = landed?.step()) {
            null -> {
                CorrectPersonalDataOutcome.Unavailable
            }

            is Step.Stop -> {
                step.outcome
            }

            is Step.Go -> {
                CorrectPersonalDataOutcome.Corrected(
                    command.caseId,
                    command.field,
                    command.correctedValue,
                    version.recordedAt,
                )
            }
        }
    }

    private fun key(
        owner: String,
        command: CorrectPersonalDataCommand,
        refs: Refs,
    ): String =
        uuidV5(
            PRIVACY_KEY_NAMESPACE,
            listOf(
                owner,
                command.caseId,
                refs.counterpartyRef,
                refs.ackId,
                command.field,
                command.correctedValue,
                command.reason,
            ).joinToString("|"),
        ).toString()

    private fun uuidV5(
        namespace: UUID,
        name: String,
    ): UUID {
        val ns =
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array()
        val h = MessageDigest.getInstance("SHA-1").digest(ns + name.toByteArray(Charsets.UTF_8))
        h[VERSION_BYTE] = ((h[VERSION_BYTE].toInt() and VERSION_MASK) or VERSION_5).toByte()
        h[VARIANT_BYTE] = ((h[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val b = ByteBuffer.wrap(h, 0, UUID_BYTES)
        return UUID(b.long, b.long)
    }
}
