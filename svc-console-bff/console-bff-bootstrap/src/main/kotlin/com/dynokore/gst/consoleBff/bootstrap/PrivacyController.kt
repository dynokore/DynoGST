package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalData
import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalDataCommand
import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalDataOutcome
import com.dynokore.gst.consoleBff.contract.api.PrivacyApi
import com.dynokore.gst.consoleBff.contract.model.CorrectPersonalDataRequest
import com.dynokore.gst.consoleBff.contract.model.CorrectPersonalDataResult
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.time.ZoneOffset
import java.util.UUID

/**
 * POST /bff/privacy-cases/{caseId}/corrections (INV-REQ-886): routes through [CorrectPersonalData], which decides
 * `privacy-request.handle` per request (INV-Q-10027); no role is compared. The contract makes entityRef,
 * counterpartyRef and ackId optional, console-bff requires them: a missing one reaches the use case as null and is
 * answered 400 SRT-001 there, before any owner is called.
 */
@RestController
open class PrivacyController(
    private val correctPersonalData: CorrectPersonalData,
    private val problems: PrivacyProblemMapper,
) : PrivacyApi {
    override fun correctPersonalData(
        caseId: UUID,
        correctPersonalDataRequest: CorrectPersonalDataRequest,
    ): ResponseEntity<CorrectPersonalDataResult> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) return refused(problems.unauthenticated(correlation()))
        val body = correctPersonalDataRequest
        val command =
            CorrectPersonalDataCommand(
                tenantId = tenant,
                principalId = principal,
                entityRef = body.entityRef,
                counterpartyRef = body.counterpartyRef,
                ackId = body.ackId,
                caseId = caseId.toString(),
                field = body.field,
                correctedValue = body.correctedValue,
                reason = body.reason,
            )
        return when (val outcome = correctPersonalData.handle(command)) {
            is CorrectPersonalDataOutcome.Corrected -> {
                ResponseEntity.ok(
                    CorrectPersonalDataResult(
                        caseId = caseId,
                        field = outcome.field,
                        correctedValue = outcome.correctedValue,
                        correctedAt = outcome.correctedAt.atOffset(ZoneOffset.UTC),
                    ),
                )
            }

            else -> {
                refused(checkNotNull(problems.refusal(outcome, correlation())))
            }
        }
    }

    /** A body the model cannot read or bounds (wrong type, over a maximum, empty identifier): SRT-001, no use case. */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
    )
    fun unreadableBody(): ResponseEntity<String> {
        val answer = problems.contractViolation("the request body is not valid", correlation())
        return ResponseEntity.status(answer.status).header("Content-Type", Problems.CONTENT_TYPE).body(answer.body)
    }

    /** The generated signature names the 200 body only; the problem body is a string, so the type is erased here. */
    @Suppress("UNCHECKED_CAST")
    private fun refused(answer: StoredResponse): ResponseEntity<CorrectPersonalDataResult> =
        ResponseEntity
            .status(answer.status)
            .header("Content-Type", Problems.CONTENT_TYPE)
            .body(answer.body) as ResponseEntity<CorrectPersonalDataResult>

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
}
