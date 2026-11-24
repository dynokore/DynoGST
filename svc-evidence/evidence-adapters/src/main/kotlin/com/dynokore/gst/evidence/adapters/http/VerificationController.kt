package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.artefact.ListRenditionSampleChecks
import com.dynokore.gst.evidence.application.artefact.ListRenditionSampleChecksResult
import com.dynokore.gst.evidence.contract.api.VerificationApi
import com.dynokore.gst.evidence.contract.model.SampleCheckArtefact
import com.dynokore.gst.evidence.contract.model.SampleCheckArtefactPage
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.time.ZoneOffset
import java.util.UUID

/**
 * ARC-19, CS-14: a request outside the contract's bounds (the generated `@Min`/`@Max` of `limit`) is rejected by method
 * validation before the controller runs; it becomes the closed SRT-001 envelope, never a 500.
 */
@RestControllerAdvice(assignableTypes = [VerificationController::class])
class VerificationRefusalAdvice {
    @ExceptionHandler(ConstraintViolationException::class, HandlerMethodValidationException::class)
    fun refuse(): ResponseEntity<String> = VerificationController.envelope(ClosedErrorCode.CONTRACT_VIOLATION)
}

/**
 * The verification operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it
 * in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `listRenditionSampleChecks` (INV-T-27618; INV-REQ-1036): no verified tenant or user 401; then the use case decides
 * the key `rendition-evidence.view` per request (fail closed): Forbidden 403, Invalid (limit or cursor) 400, else the
 * page with `nextCursor` omitted on the last page.
 */
@RestController
open class VerificationController(
    private val sampleChecks: ListRenditionSampleChecks,
    private val requests: ObjectProvider<HttpServletRequest>,
) : VerificationApi {
    override fun listRenditionSampleChecks(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<SampleCheckArtefactPage> {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        val tenant = caller?.tenant
        val answer =
            if (tenant == null || caller.actor.isBlank()) {
                refusal(ClosedErrorCode.UNAUTHENTICATED)
            } else {
                when (
                    val result =
                        sampleChecks.list(
                            tenant,
                            Principal(caller.actor, PrincipalKind.HUMAN),
                            entityRef,
                            limit,
                            cursor,
                        )
                ) {
                    is ListRenditionSampleChecksResult.Page -> ResponseEntity.ok(page(result))
                    ListRenditionSampleChecksResult.Forbidden -> refusal(ClosedErrorCode.ACCESS_DENIED)
                    ListRenditionSampleChecksResult.Invalid -> refusal(ClosedErrorCode.CONTRACT_VIOLATION)
                }
            }
        return answer
    }

    private fun page(result: ListRenditionSampleChecksResult.Page) =
        SampleCheckArtefactPage(
            items =
                result.items.map {
                    SampleCheckArtefact(
                        artefactType = SampleCheckArtefact.ArtefactType.valueOf(it.artefactType),
                        reference = it.reference,
                        hash = it.hash,
                        recordedAt = it.recordedAt.atOffset(ZoneOffset.UTC),
                    )
                },
            nextCursor = result.nextCursor,
        )

    private fun refusal(code: ClosedErrorCode): ResponseEntity<SampleCheckArtefactPage> {
        @Suppress("UNCHECKED_CAST") // the body is the problem text; the envelope is written as it is
        return envelope(code) as ResponseEntity<SampleCheckArtefactPage>
    }

    companion object {
        /** The API-20 envelope of one closed service-runtime case (as `LookupRefusalAdvice` renders it). */
        fun envelope(code: ClosedErrorCode): ResponseEntity<String> {
            val problem =
                Problems.closed(code, CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString())
            val headers = HttpHeaders()
            headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
        }
    }
}
