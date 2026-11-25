package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.application.document.DocumentIdentityConflict
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.RelayedRefusal

/**
 * The 409 problem of a [DocumentIdentityConflict] (SUB-007, INV-AC-27932). The answer holds only catalogue text and the
 * correlation id: no identity value of the refused document and nothing of the conflict's cause.
 */
object DocumentIdentityConflictProblem {
    private const val CODE = "SUB-007"
    private const val HTTP_CONFLICT = 409

    fun render(
        conflict: DocumentIdentityConflict,
        correlationId: String,
    ): ProblemResponse {
        require(conflict.code == CODE) { "This mapper renders only $CODE" }
        return Problems.renderRelayed(
            RelayedRefusal(
                status = HTTP_CONFLICT,
                code = conflict.code,
                title = "Same commercial document under two Doc UUIDs",
                severity = "BLOCK",
                disposition = "TERMINAL",
                remediationOwner = "Platform",
            ),
            correlationId,
        )
    }
}
