package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.EvidencePage
import com.dynokore.gst.consoleBff.application.renditionevidence.GetEntityRenditionEvidence
import com.dynokore.gst.consoleBff.application.renditionevidence.RenditionEvidenceResult
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckArtefactRecord
import com.dynokore.gst.consoleBff.contract.api.EvidenceApi
import com.dynokore.gst.consoleBff.contract.model.EntityRenditionEvidence
import com.dynokore.gst.consoleBff.contract.model.RenditionAttestationRecord
import com.dynokore.gst.consoleBff.contract.model.RenditionAttestationRecordPage
import com.dynokore.gst.consoleBff.contract.model.SampleCheckArtefact
import com.dynokore.gst.consoleBff.contract.model.SampleCheckArtefactPage
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.time.ZoneOffset
import java.util.UUID

/**
 * GET /bff/entities/{entityRef}/rendition-evidence (INV-REQ-1036): the two pages of [GetEntityRenditionEvidence].
 * The read filter decides `rendition-evidence.view` on the entity of the path before this class runs (INV-Q-10027);
 * nothing is decided again here and no role is read. An owner's refusal is relayed with its envelope, an unavailable
 * owner is the closed 503, and a record the contract cannot carry is answered like an unavailable owner (OWASP ASVS
 * V4.1.1: fail closed, no value is guessed and no record is dropped). The other operations of [EvidenceApi] keep the
 * generated 501.
 */
@RestController
open class RenditionEvidenceController(
    private val getEntityRenditionEvidence: GetEntityRenditionEvidence,
    private val problems: OverridesProblemMapper,
) : EvidenceApi {
    override fun getEntityRenditionEvidence(
        entityRef: String,
        limit: Int,
        attestationsCursor: String?,
        sampleChecksCursor: String?,
    ): ResponseEntity<EntityRenditionEvidence> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        return when {
            principal == null || tenant == null -> {
                refused(problems.unauthenticated(correlation()))
            }

            // the generated validation bounds the maximum only
            limit !in 1..MAX_LIMIT -> {
                refused(problems.contractViolation("limit is out of bounds", correlation()))
            }

            else -> {
                answer(getEntityRenditionEvidence.get(entityRef, limit, attestationsCursor, sampleChecksCursor))
            }
        }
    }

    private fun answer(result: RenditionEvidenceResult): ResponseEntity<EntityRenditionEvidence> =
        when (result) {
            is RenditionEvidenceResult.Composed -> composed(result)
            is RenditionEvidenceResult.Forbidden -> refused(problems.relayed(result.refusal, correlation()))
            is RenditionEvidenceResult.NotFound -> refused(problems.relayed(result.refusal, correlation()))
            is RenditionEvidenceResult.Invalid -> refused(problems.relayed(result.refusal, correlation()))
            is RenditionEvidenceResult.Unavailable -> refused(problems.unavailable(correlation()))
        }

    /**
     * A parameter the contract bounds (entityRef over 40 characters) fails the generated validation. The handlers read
     * no property: they are final, so on the validation proxy of this class they would find none.
     */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameter(): ResponseEntity<String> =
        write(Problems.contractViolation("a request parameter is out of bounds", correlation()))

    /** jakarta's ConstraintViolationException is matched by class name (no compile dependency on it). */
    @ExceptionHandler(RuntimeException::class)
    fun constraintViolation(failure: RuntimeException): ResponseEntity<String> {
        if (failure.javaClass.name != CONSTRAINT_VIOLATION) throw failure
        return invalidParameter()
    }

    private fun composed(result: RenditionEvidenceResult.Composed): ResponseEntity<EntityRenditionEvidence> {
        val attestations = attestationPage(result.attestations)
        val sampleChecks = sampleCheckPage(result.sampleChecks)
        return if (attestations == null || sampleChecks == null) {
            refused(problems.unavailable(correlation()))
        } else {
            ResponseEntity.ok(EntityRenditionEvidence(attestations, sampleChecks))
        }
    }

    /** The generated signature names the 200 body only; the problem body is a string, so the type is erased here. */
    @Suppress("UNCHECKED_CAST")
    private fun refused(answer: StoredResponse): ResponseEntity<EntityRenditionEvidence> =
        write(answer) as ResponseEntity<EntityRenditionEvidence>

    private fun write(answer: StoredResponse): ResponseEntity<String> =
        ResponseEntity
            .status(answer.status)
            .header("Content-Type", Problems.CONTENT_TYPE)
            .body(answer.body)

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"

    private companion object {
        /** The `maximum` of the limit parameter in the pinned contract. */
        const val MAX_LIMIT = 500
        const val CONSTRAINT_VIOLATION = "jakarta.validation.ConstraintViolationException"
    }
}

/** Null when a renderer is no value of the contract: no partial page. */
private fun attestationPage(page: EvidencePage<AttestationRecord>): RenditionAttestationRecordPage? {
    val items = page.items.mapNotNull(::attestation)
    return if (items.size == page.items.size) RenditionAttestationRecordPage(items, page.nextCursor) else null
}

private fun attestation(record: AttestationRecord): RenditionAttestationRecord? =
    RenditionAttestationRecord.Renderer.entries
        .firstOrNull { it.value == record.renderer }
        ?.let {
            RenditionAttestationRecord(
                documentClass = record.documentClass,
                renderer = it,
                attestedBy = record.attestedBy,
                attestedAt = record.attestedAt.atOffset(ZoneOffset.UTC),
                sequence = record.sequence,
            )
        }

/** Null when an artefact type is no value of the contract: no partial page. */
private fun sampleCheckPage(page: EvidencePage<SampleCheckArtefactRecord>): SampleCheckArtefactPage? {
    val items = page.items.mapNotNull(::sampleCheck)
    return if (items.size == page.items.size) SampleCheckArtefactPage(items, page.nextCursor) else null
}

private fun sampleCheck(record: SampleCheckArtefactRecord): SampleCheckArtefact? =
    SampleCheckArtefact.ArtefactType.entries
        .firstOrNull { it.value == record.artefactType }
        ?.let {
            SampleCheckArtefact(
                artefactType = it,
                reference = record.reference,
                hash = record.hash,
                recordedAt = record.recordedAt.atOffset(ZoneOffset.UTC),
            )
        }
