package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore

private const val SAMPLE_CHECK = "SAMPLE_CHECK"
private const val MAX_READS_IN_FLIGHT = 8

/** One item of the sample check page. Every artefact of type SAMPLE_CHECK has its [sample], which holds no byte. */
data class SampleCheckEntry(
    val artefact: SampleCheckArtefactRecord,
    val sample: SampleMeta?,
)

/** The composed page with the stored sample metadata of each sample check, or no page at all. */
sealed interface RenditionSampleEvidenceResult {
    /**
     * Every identity check of the page that failed, in page order, whichever kind of result is given. The caller logs
     * each of them (reference and rule, never bytes).
     */
    val integrityFailures: List<Verification.Failed>

    /** [standingOpen] is the PAY-014 standing of the entity; null when there is none to show (an empty page). */
    data class Composed(
        val attestations: EvidencePage<AttestationRecord>,
        val sampleChecks: EvidencePage<SampleCheckEntry>,
        val standingOpen: Boolean?,
    ) : RenditionSampleEvidenceResult {
        override val integrityFailures: List<Verification.Failed>
            get() = emptyList()
    }

    data class Forbidden(
        val refusal: OwnerRefusal,
        override val integrityFailures: List<Verification.Failed>,
    ) : RenditionSampleEvidenceResult

    data class NotFound(
        val refusal: OwnerRefusal,
        override val integrityFailures: List<Verification.Failed>,
    ) : RenditionSampleEvidenceResult

    data class Invalid(
        val refusal: OwnerRefusal,
        override val integrityFailures: List<Verification.Failed>,
    ) : RenditionSampleEvidenceResult

    data class Unavailable(
        val reason: String,
        override val integrityFailures: List<Verification.Failed>,
    ) : RenditionSampleEvidenceResult

    /** A sample record is not the one its artefact reference names, and no payload read was refused or unavailable. */
    data class IntegrityFailure(
        override val integrityFailures: List<Verification.Failed>,
    ) : RenditionSampleEvidenceResult {
        init {
            require(integrityFailures.isNotEmpty()) { "an integrity failure names at least one failed check" }
        }
    }
}

/**
 * INV-REQ-1036: the page of [GetEntityRenditionEvidence], which is asked once, with each SAMPLE_CHECK item joined
 * with its payload metadata. The metadata holds no byte, so no hash is checked here. A page the join does not compose
 * is relayed as its kind, with no payload read.
 *
 * It fails closed: one failed item fails the whole answer, so no page is returned in which a sample check has no
 * sample. With several failed items the order is Unavailable, Forbidden, NotFound, Invalid, integrity failure; among
 * failures of one kind the first in page order is returned. The permission is decided by the read filter, not here.
 *
 * At most [MAX_READS_IN_FLIGHT] reads run at once on [readExecutor]. Its `execute` is called on the thread of the
 * caller, and the composition root must hand over an executor that carries the request context of that thread (the
 * deadline of the operation and the end-user token, both scoped values) to the thread that runs the read.
 */
class GetEntityRenditionEvidenceWithSamples(
    private val evidence: GetEntityRenditionEvidence,
    private val sampleMeta: SampleMetaRead,
    private val readExecutor: Executor,
) {
    fun get(
        entityRef: String,
        limit: Int,
        attestationsCursor: String?,
        sampleChecksCursor: String?,
    ): RenditionSampleEvidenceResult =
        when (val result = evidence.get(entityRef, limit, attestationsCursor, sampleChecksCursor)) {
            is RenditionEvidenceResult.Composed -> {
                joined(entityRef, result)
            }

            is RenditionEvidenceResult.Forbidden -> {
                RenditionSampleEvidenceResult.Forbidden(result.refusal, emptyList())
            }

            is RenditionEvidenceResult.NotFound -> {
                RenditionSampleEvidenceResult.NotFound(result.refusal, emptyList())
            }

            is RenditionEvidenceResult.Invalid -> {
                RenditionSampleEvidenceResult.Invalid(result.refusal, emptyList())
            }

            is RenditionEvidenceResult.Unavailable -> {
                RenditionSampleEvidenceResult.Unavailable(result.reason, emptyList())
            }
        }

    private fun joined(
        entityRef: String,
        page: RenditionEvidenceResult.Composed,
    ): RenditionSampleEvidenceResult {
        val joins = boundedMap(page.sampleChecks.items) { join(entityRef, it) }
        val integrityFailures = joins.filterIsInstance<Join.Mismatch>().map { it.failure }
        val done = joins.filterIsInstance<Join.Done>()
        return refused(joins.filterIsInstance<Join.Refused>().map { it.reply }, integrityFailures)
            ?: if (integrityFailures.isEmpty()) {
                RenditionSampleEvidenceResult.Composed(
                    page.attestations,
                    EvidencePage(done.map { it.entry }, page.sampleChecks.nextCursor),
                    done.firstOrNull()?.standingOpen,
                )
            } else {
                RenditionSampleEvidenceResult.IntegrityFailure(integrityFailures)
            }
    }

    private fun join(
        entityRef: String,
        artefact: SampleCheckArtefactRecord,
    ): Join {
        if (artefact.artefactType != SAMPLE_CHECK) return Join.Done(SampleCheckEntry(artefact, null), null)
        val checkId = SampleRecordVerifier.checkIdOf(entityRef, artefact.reference)
        return if (checkId == null) {
            Join.Mismatch(Verification.Failed(artefact.reference, SampleCheckRule.REFERENCE))
        } else {
            read(entityRef, checkId, artefact)
        }
    }

    private fun read(
        entityRef: String,
        checkId: String,
        artefact: SampleCheckArtefactRecord,
    ): Join =
        when (val reply = sampleMeta.read(entityRef, checkId)) {
            is SampleReply.Ok -> identified(entityRef, checkId, artefact, reply.value)
            else -> Join.Refused(reply)
        }

    private fun identified(
        entityRef: String,
        checkId: String,
        artefact: SampleCheckArtefactRecord,
        reply: SampleMetaReply,
    ): Join =
        when (val outcome = SampleRecordVerifier.identity(entityRef, checkId, reply.meta)) {
            is Verification.Failed -> Join.Mismatch(outcome)
            Verification.Verified -> Join.Done(SampleCheckEntry(artefact, reply.meta), reply.standingOpen)
        }

    /** The one answer for the payload reads that gave no record, or null when each gave one. */
    private fun refused(
        replies: List<SampleReply<SampleMetaReply>>,
        integrityFailures: List<Verification.Failed>,
    ): RenditionSampleEvidenceResult? {
        val unavailable = replies.filterIsInstance<SampleReply.Unavailable>().firstOrNull()
        val forbidden = replies.filterIsInstance<SampleReply.Forbidden>().firstOrNull()
        val notFound = replies.filterIsInstance<SampleReply.NotFound>().firstOrNull()
        val invalid = replies.filterIsInstance<SampleReply.Invalid>().firstOrNull()
        return when {
            unavailable != null -> {
                RenditionSampleEvidenceResult.Unavailable(unavailable.reason, integrityFailures)
            }

            forbidden != null -> {
                RenditionSampleEvidenceResult.Forbidden(forbidden.refusal, integrityFailures)
            }

            notFound != null -> {
                RenditionSampleEvidenceResult.NotFound(notFound.refusal, integrityFailures)
            }

            else -> {
                invalid?.let { RenditionSampleEvidenceResult.Invalid(it.refusal, integrityFailures) }
            }
        }
    }

    /** [read] of every item, at most [MAX_READS_IN_FLIGHT] at once, with the results in the order of [items]. */
    private fun <T, R> boundedMap(
        items: List<T>,
        read: (T) -> R,
    ): List<R> {
        val slots = Semaphore(MAX_READS_IN_FLIGHT)
        val tasks =
            items.map { item ->
                slots.acquire()
                val task =
                    FutureTask {
                        try {
                            read(item)
                        } finally {
                            slots.release()
                        }
                    }
                readExecutor.execute(task)
                task
            }
        return tasks.map { it.get() }
    }

    private sealed interface Join {
        data class Done(
            val entry: SampleCheckEntry,
            val standingOpen: Boolean?,
        ) : Join

        /** A payload read that gave no record. */
        data class Refused(
            val reply: SampleReply<SampleMetaReply>,
        ) : Join

        data class Mismatch(
            val failure: Verification.Failed,
        ) : Join
    }
}
