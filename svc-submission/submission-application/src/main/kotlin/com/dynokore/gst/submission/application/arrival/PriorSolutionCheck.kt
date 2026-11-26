package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.migration.ImportedPriorEvidenceStore
import com.dynokore.gst.submission.application.migration.RaisePriorSolutionHold
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.startdate.MigrationCutoverDates
import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision
import com.dynokore.gst.submission.domain.migration.PriorSolutionDocument
import com.dynokore.gst.submission.domain.migration.PriorSolutionGuard
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The result of [PriorSolutionCheck.check]. */
sealed interface PriorSolutionOutcome {
    data object Held : PriorSolutionOutcome

    /** The version matches imported prior evidence: it is never transmitted (INV-REQ-1109). No hold is raised. */
    data object ExternallySubmitted : PriorSolutionOutcome

    data object NotHeld : PriorSolutionOutcome
}

/**
 * Holds a version that [PriorSolutionGuard] holds with SUB-017 (INV-REQ-1110, INV-AC-28449) and answers
 * [PriorSolutionOutcome.ExternallySubmitted] for one the guard matches to imported prior evidence (INV-REQ-1109,
 * INV-AC-28853), writing nothing for it. It opens no transaction
 * (the caller's handler does, so the hold row and the ready row commit together) and holds no transport or queue.
 *
 * It reads the entity's cutover date first and then the states of the version rows of the Doc UUID. With no cutover
 * date and no `EXTERNALLY_SUBMITTED` version the guard can only answer `NotAffected`, so the answer is
 * [PriorSolutionOutcome.NotHeld] and neither the document facts nor the imported records are read. In every other case
 * both are read. A fact that is needed and cannot be read throws [CollaboratorUnavailableException] before any write
 * (fail closed). Every remaining decision is [PriorSolutionOutcome.NotHeld].
 */
@Suppress("LongParameterList") // one port per guard fact, the hold use case and the decision
class PriorSolutionCheck(
    private val documents: ArrivingDocumentFacts,
    private val cutoverDates: MigrationCutoverDates,
    private val evidence: ImportedPriorEvidenceStore,
    private val inFlight: InFlightStore,
    private val raise: RaisePriorSolutionHold,
    private val decide: (PriorSolutionDocument, LocalDate?, List<ImportedPriorRecord>) -> PriorSolutionDecision =
        PriorSolutionGuard::decide,
) {
    fun check(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        entityRef: String,
    ): PriorSolutionOutcome {
        val cutoverDate = read("cutover date") { cutoverDates.find(tenant, entityRef) }
        val states = read("version states") { inFlight.lockVersions(tenant, docUuid).map { it.state } }
        return if (cutoverDate == null && states.none { it == LifecycleState.EXTERNALLY_SUBMITTED }) {
            PriorSolutionOutcome.NotHeld
        } else {
            decideOnFacts(tenant, docUuid, version, entityRef, cutoverDate)
        }
    }

    private fun decideOnFacts(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        entityRef: String,
        cutoverDate: LocalDate?,
    ): PriorSolutionOutcome {
        val facts =
            read("document facts") { documents.of(tenant, docUuid, version) }
                ?: throw CollaboratorUnavailableException(
                    "the document facts of $docUuid version $version are unavailable",
                )
        val imported = read("imported prior records") { evidence.listFor(tenant, entityRef) }
        val document =
            PriorSolutionDocument(
                entityRef = entityRef,
                documentNumber = facts.documentNumber,
                transactionDate = facts.transactionDate,
                creditNote = facts.creditNote,
                precedingDocumentNumber = facts.precedingDocumentNumber,
                precedingDocumentDate = facts.precedingDocumentDate,
                versions = facts.versions,
            )
        val decision = decide(document, cutoverDate, imported)
        return when {
            decision is PriorSolutionDecision.ExternallySubmitted -> {
                PriorSolutionOutcome.ExternallySubmitted
            }

            decision is PriorSolutionDecision.HoldForCorrection && decision.code == PriorSolutionGuard.SUB_017 -> {
                raise.execute(tenant, docUuid, version, entityRef, decision)
                PriorSolutionOutcome.Held
            }

            else -> {
                PriorSolutionOutcome.NotHeld
            }
        }
    }

    private fun <T> read(
        fact: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            throw CollaboratorUnavailableException("the $fact read failed", e)
        }
}
