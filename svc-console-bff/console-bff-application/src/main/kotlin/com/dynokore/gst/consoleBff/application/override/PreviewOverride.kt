package com.dynokore.gst.consoleBff.application.override

/** The two plain-language consequence texts of an overridable code. */
data class ConsequenceText(
    val willTransmit: String,
    val residualRisk: String,
)

/**
 * The catalogue's texts, the one source of the disposition: they exist only on OVERRIDABLE codes, so null means the
 * code is not OVERRIDABLE or not in the catalogue.
 */
fun interface ConsequenceTexts {
    fun of(code: String): ConsequenceText?
}

enum class PreviewDecision { ALLOW }

enum class PreviewApprovalKind { ONE_APPROVER }

sealed interface PreviewOutcome {
    data class Previewed(
        val exceptionId: String,
        val docUuid: String,
        val docVersion: Long,
        val code: String,
        val permissionKey: String,
        val decision: PreviewDecision,
        val approvalKind: PreviewApprovalKind,
        val willTransmit: String,
        val residualRisk: String,
    ) : PreviewOutcome

    data object NotFound : PreviewOutcome

    data object Forbidden : PreviewOutcome

    data object NotPreviewable : PreviewOutcome

    data class Relayed(
        val refusal: OwnerRefusal,
    ) : PreviewOutcome

    data class Unavailable(
        val reason: String,
    ) : PreviewOutcome
}

/** Read-only preview of a document-scoped override: at most one owner read, never a command. */
class PreviewOverride(
    private val targets: OverrideTargets,
    private val authority: OverrideAuthority,
    private val texts: ConsequenceTexts,
    private val payload: PayloadOverrides,
) {
    fun preview(
        tenantId: String,
        userId: String,
        exceptionId: String,
    ): PreviewOutcome {
        val row = targets.find(exceptionId)?.takeIf { it.state == OPEN } ?: return PreviewOutcome.NotFound
        return if (!authority.holds(tenantId, userId, row.entityRef)) {
            PreviewOutcome.Forbidden
        } else {
            described(row)
        }
    }

    private fun described(row: OverridableRow): PreviewOutcome {
        val text = texts.of(row.code)
        return when {
            // A period-scoped (SUBMISSION) code is refused too: the preview describes a document and its version.
            text == null || RouteTable.routeOf(row.code) != Route.PAYLOAD -> PreviewOutcome.NotPreviewable

            row.docUuid.isEmpty() -> PreviewOutcome.NotFound

            else -> versioned(row, text)
        }
    }

    private fun versioned(
        row: OverridableRow,
        text: ConsequenceText,
    ): PreviewOutcome =
        when (val read = payload.latestVersion(row.docUuid)) {
            is OwnerReply.Ok -> {
                if (read.value < FIRST_VERSION) {
                    PreviewOutcome.Unavailable("document version below $FIRST_VERSION")
                } else {
                    previewed(row, read.value, text)
                }
            }

            is OwnerReply.Refused -> {
                PreviewOutcome.Relayed(read.refusal)
            }

            is OwnerReply.Unavailable -> {
                PreviewOutcome.Unavailable(read.reason)
            }
        }

    private fun previewed(
        row: OverridableRow,
        version: Long,
        text: ConsequenceText,
    ) = PreviewOutcome.Previewed(
        exceptionId = row.exceptionId,
        docUuid = row.docUuid,
        docVersion = version,
        code = row.code,
        permissionKey = VALIDATION_OVERRIDE,
        // A denied caller never reaches here.
        decision = PreviewDecision.ALLOW,
        // INV-REQ-943: only the default approval rule is offered until its options are covered.
        approvalKind = PreviewApprovalKind.ONE_APPROVER,
        willTransmit = text.willTransmit,
        residualRisk = text.residualRisk,
    )

    private companion object {
        const val OPEN = "OPEN"
        const val FIRST_VERSION = 1L
    }
}
