package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalDataOutcome
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.RelayedRefusal
import com.dynokore.platform.serviceruntime.http.StoredResponse

/**
 * The refusals of correctPersonalData (RFC 9457), each as application/problem+json through [Problems] with the
 * correlation id of the request being served: Invalid is 400 SRT-001, Forbidden 403 SRT-004, NotFound 404 SRT-005,
 * the owner's refusal is relayed unchanged, and an unavailable owner is the closed 503 SRT-013 with no member of the
 * owner's text. Modelled on [OverridesProblemMapper].
 */
class PrivacyProblemMapper {
    fun contractViolation(
        detail: String,
        correlationId: String,
    ): StoredResponse = Problems.contractViolation(detail, correlationId)

    fun unauthenticated(correlationId: String): StoredResponse = Problems.unauthenticated(correlationId)

    /** The refusal answer of a use case outcome; null for [CorrectPersonalDataOutcome.Corrected]. */
    fun refusal(
        outcome: CorrectPersonalDataOutcome,
        correlationId: String,
    ): StoredResponse? =
        when (outcome) {
            is CorrectPersonalDataOutcome.Corrected -> null
            CorrectPersonalDataOutcome.Invalid -> contractViolation("the request is not valid", correlationId)
            CorrectPersonalDataOutcome.Forbidden -> Problems.accessDenied(correlationId)
            CorrectPersonalDataOutcome.NotFound -> Problems.notFound(correlationId)
            is CorrectPersonalDataOutcome.Relayed -> relayed(outcome, correlationId)
            CorrectPersonalDataOutcome.Unavailable -> unavailable(correlationId)
        }

    private fun unavailable(correlationId: String): StoredResponse =
        stored(Problems.render(ClosedErrorCode.SERVICE_NOT_READY, correlationId))

    private fun relayed(
        outcome: CorrectPersonalDataOutcome.Relayed,
        correlationId: String,
    ): StoredResponse {
        val refusal = outcome.refusal
        return stored(
            Problems.renderRelayed(
                RelayedRefusal(
                    status = refusal.status,
                    code = refusal.code,
                    title = refusal.title,
                    severity = refusal.severity,
                    disposition = refusal.disposition,
                    remediationOwner = refusal.remediationOwner,
                    detail = refusal.detail,
                ),
                correlationId,
            ),
        )
    }

    private fun stored(response: ProblemResponse) = StoredResponse(response.status, response.body)
}
