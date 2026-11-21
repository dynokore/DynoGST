package com.dynokore.gst.ingestion.application.fallback

import com.dynokore.gst.ingestion.domain.fallback.AfterTheFactCheck
import com.dynokore.gst.ingestion.domain.fallback.FallbackDecision
import com.dynokore.gst.ingestion.domain.fallback.FallbackInput
import com.dynokore.gst.ingestion.domain.fallback.FallbackReason
import com.dynokore.gst.ingestion.domain.fallback.IssuanceMode
import com.dynokore.gst.ingestion.domain.fallback.NotFallbackReason
import com.dynokore.gst.ingestion.domain.fallback.RefusalReason
import com.dynokore.gst.ingestion.domain.fallback.RelayFallbackDetection
import java.time.LocalDate
import java.util.UUID

/** A posting received by ingestion-svc, with the entity and period it belongs to (INV-REQ-1321). */
data class BindFallbackCommand(
    val tenantId: UUID,
    val sourceSystem: String,
    val sourceKey: String,
    val entityRef: String,
    val periodRef: String,
    val networkIssuancePosted: Boolean,
    val relayIssuance: Boolean,
    val documentDate: LocalDate,
)

/** The issuance mode in force for the entity on [date]; null means unknown. */
fun interface IssuanceModeReader {
    fun modeOn(
        tenantId: UUID,
        entityRef: String,
        date: LocalDate,
    ): IssuanceMode?
}

data class FallbackBinding(
    val tenantId: UUID,
    val sourceSystem: String,
    val sourceKey: String,
    val entityRef: String,
    val periodRef: String,
    val reason: FallbackReason,
)

enum class BindStored { CREATED, ALREADY_BOUND }

/**
 * Stores the binding and the queue entries for [checks] together, in one transaction, and is idempotent on
 * (tenantId, sourceSystem, sourceKey): a repeat stores and queues nothing and answers [BindStored.ALREADY_BOUND].
 */
fun interface FallbackBindingStore {
    fun bindOnce(
        binding: FallbackBinding,
        checks: List<AfterTheFactCheck>,
    ): BindStored
}

sealed interface BindOutcome {
    data class Bound(
        val binding: FallbackBinding,
        val checks: List<AfterTheFactCheck>,
    ) : BindOutcome

    data class AlreadyBound(
        val binding: FallbackBinding,
    ) : BindOutcome

    data class NotFallback(
        val reason: NotFallbackReason,
    ) : BindOutcome

    data class Refused(
        val reason: RefusalReason,
    ) : BindOutcome
}

/**
 * Binds a fallback-issued document once and queues its after-the-fact checks once (INV-REQ-1321 behavior 4): the
 * pure decision [RelayFallbackDetection.decide] runs on the mode in force on the document date; only a Fallback
 * reaches the store.
 */
class BindFallbackDocument(
    private val modes: IssuanceModeReader,
    private val store: FallbackBindingStore,
) {
    fun execute(command: BindFallbackCommand): BindOutcome {
        val mode =
            if (command.networkIssuancePosted && !command.relayIssuance) {
                modes.modeOn(command.tenantId, command.entityRef, command.documentDate)
            } else {
                null
            }
        val decision =
            RelayFallbackDetection.decide(
                FallbackInput(command.networkIssuancePosted, command.relayIssuance, mode),
            )
        return when (decision) {
            is FallbackDecision.NotFallback -> {
                BindOutcome.NotFallback(decision.reason)
            }

            is FallbackDecision.Refused -> {
                BindOutcome.Refused(decision.reason)
            }

            is FallbackDecision.Fallback -> {
                val binding =
                    FallbackBinding(
                        command.tenantId,
                        command.sourceSystem,
                        command.sourceKey,
                        command.entityRef,
                        command.periodRef,
                        decision.reason,
                    )
                when (store.bindOnce(binding, decision.checks)) {
                    BindStored.CREATED -> BindOutcome.Bound(binding, decision.checks)
                    BindStored.ALREADY_BOUND -> BindOutcome.AlreadyBound(binding)
                }
            }
        }
    }
}
