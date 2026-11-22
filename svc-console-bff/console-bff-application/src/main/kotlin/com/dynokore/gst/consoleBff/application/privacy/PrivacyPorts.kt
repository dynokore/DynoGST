package com.dynokore.gst.consoleBff.application.privacy

import com.dynokore.gst.consoleBff.application.override.OwnerReply
import java.time.Instant

/** The single scoped permission key of the personal-data correction. */
const val PRIVACY_REQUEST_HANDLE = "privacy-request.handle"

/** Decided per request by AuthorityReplica.decide in the adapter; no role is read. */
fun interface PrivacyAuthority {
    fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
    ): Boolean
}

/** The `status` member of evidence-svc getCase; null for an unknown case. */
fun interface PrivacyCases {
    fun status(caseId: String): OwnerReply<String?>
}

data class CorrectionRequest(
    val entityRef: String,
    val caseId: String,
    val field: String,
    val correctedValue: String,
    val reason: String,
)

data class CorrectedVersion(
    val recordedAt: Instant,
)

fun interface CounterpartyCorrections {
    fun correct(
        counterpartyRef: String,
        idempotencyKey: String,
        request: CorrectionRequest,
    ): OwnerReply<CorrectedVersion>
}

/** Carries the field name and the reason only; never a previous or corrected value. */
fun interface EvidenceAnnotations {
    fun append(
        caseId: String,
        idempotencyKey: String,
        ackId: String,
        correctedField: String,
        reason: String,
    ): OwnerReply<Unit>
}
