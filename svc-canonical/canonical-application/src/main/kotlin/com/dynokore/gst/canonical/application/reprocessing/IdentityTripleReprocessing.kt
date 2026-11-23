package com.dynokore.gst.canonical.application.reprocessing

import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** The current version of a document as the reprocessing reads it: its stored content and the facts of its event. */
data class CurrentDocumentVersion(
    val version: Int,
    /** The stored content of the version; [NewVersion.canonical] is the canonical JSON as stored. */
    val content: NewVersion,
    /** The facts a `CanonicalVersionCreated` of this document carries; version and canonical JSON are replaced. */
    val facts: CanonicalVersionCreatedFacts,
)

/** Reads the current version of a document of one tenant. A document of another tenant is not visible. */
fun interface CurrentDocumentVersions {
    fun read(
        tenantId: UUID,
        docUuid: UUID,
    ): CurrentDocumentVersion?
}

/** Reads and replaces the `identityTriple` value of a canonical document JSON; nothing else is touched. */
interface IdentityTripleDocuments {
    /** The stored triple, or null when the JSON has no `identityTriple`. */
    fun tripleOf(canonicalJson: String): SupplierIdentity?

    /** [canonicalJson] with only the `identityTriple` value replaced by [triple]. */
    fun withTriple(
        canonicalJson: String,
        triple: SupplierIdentity,
    ): String
}

/** The idempotency marker of the requests answered: one row per (tenant, CloudEvents id). */
fun interface IdentityTripleRequestMarkers {
    /** True when the marker was inserted (the request is new for the tenant); false when it exists already. */
    fun markIfNew(
        tenantId: UUID,
        eventId: String,
    ): Boolean
}

/** The event must be redelivered: the page rolls back whole, and the event is never dropped. */
class IdentityTripleReprocessingRetry(
    message: String,
) : RuntimeException(message)

/**
 * Answers one `IdentityTripleReprocessingRequested` event (INV-REQ-1006 behavior 2, owner option A of INV-Q-26498).
 * For each listed Doc UUID it re-resolves the identity triple on the transaction date of the current version and,
 * when it differs from the stored one, stores a new version under the same Doc UUID (the stored canonical JSON with
 * only `identityTriple` replaced) and stages `CanonicalVersionCreated`. The whole page is one transaction: the marker
 * is inserted first, a [SupplierAnswer.Retry] or [UpdateOutcome.Conflict] throws [IdentityTripleReprocessingRetry] and
 * rolls back the marker and every earlier version. The handler acts as a service under the tenant of the event;
 * [actor] is the service identity stored as `createdBy`, never a role (INV-Q-10647).
 */
@Suppress("LongParameterList") // composition root: one handler over its ports
class IdentityTripleReprocessing(
    private val transaction: TransactionRunner,
    private val markers: IdentityTripleRequestMarkers,
    private val current: CurrentDocumentVersions,
    private val documents: IdentityTripleDocuments,
    private val supplier: AggregateSupplierIdentity,
    private val versions: CreateDocumentVersion,
    private val publisher: CanonicalVersionCreatedPublisher,
    private val actor: String,
    private val log: (String) -> Unit,
) {
    init {
        require(actor.isNotBlank()) { "actor is blank" }
    }

    fun handle(
        tenantId: UUID,
        entityRef: String,
        docUuids: List<UUID>,
        eventId: String,
    ) {
        require(eventId.length in 1..MAX_EVENT_ID) { "eventId is not 1 to $MAX_EVENT_ID characters" }
        transaction.inTransaction {
            if (markers.markIfNew(tenantId, eventId)) {
                docUuids.forEach { reprocess(tenantId, entityRef, it) }
            }
        }
    }

    private fun reprocess(
        tenantId: UUID,
        entityRef: String,
        docUuid: UUID,
    ) {
        val head = current.read(tenantId, docUuid)
        val stored = head?.let { documents.tripleOf(it.content.canonical) }
        val skip =
            when {
                head == null -> "the document does not exist for the tenant"
                head.facts.entityRef != entityRef -> "the document belongs to another entity"
                stored == null -> "the canonical JSON has no identityTriple"
                else -> null
            }
        if (skip != null || head == null || stored == null) {
            log("identity triple reprocessing skipped $docUuid: $skip")
            return
        }
        val resolved =
            when (val answer = supplier.identityOf(tenantId, entityRef, head.content.transactionDate)) {
                is SupplierAnswer.Found -> {
                    answer.identity
                }

                is SupplierAnswer.Retry -> {
                    throw IdentityTripleReprocessingRetry("identity not available: ${answer.reason}")
                }
            }
        if (resolved != stored) store(docUuid, head, resolved)
    }

    private fun store(
        docUuid: UUID,
        head: CurrentDocumentVersion,
        resolved: SupplierIdentity,
    ) {
        val json = documents.withTriple(head.content.canonical, resolved)
        val next = head.content.copy(canonical = json, createdBy = actor)
        val outcome = versions.createVersion(docUuid, head.version, next)
        if (outcome !is UpdateOutcome.Updated) {
            throw IdentityTripleReprocessingRetry("the current version of $docUuid moved: $outcome")
        }
        publisher.stage(head.facts.copy(version = outcome.version.toInt(), canonicalDocument = json))
    }

    private companion object {
        const val MAX_EVENT_ID = 128
    }
}
