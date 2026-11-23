package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.preissuance.CheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.PreIssuanceCheck
import com.dynokore.gst.payload.contract.api.PreIssuanceApi
import com.dynokore.gst.payload.contract.model.DraftDocument
import com.dynokore.gst.payload.contract.model.PreIssuanceVerdict
import com.dynokore.gst.payload.contract.model.ReasonItem
import com.dynokore.gst.payload.domain.PreIssuanceReason
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * The pre-issuance operations of the payload contract (TPL-08). `checkPreIssuance` (INV-REQ-475) is stateless and
 * side-effect free (INV-REQ-325): no transaction, no store, no audit write. A draft that cannot be checked (a left-out
 * field, an amount that is not a decimal) is the API-20 envelope SRT-001 (the closed code CONTRACT_VIOLATION), decided
 * before any register lookup; a blank `supplierGstn`, `entityRef` or `currency` is refused here too, so the use case's
 * precondition is never reached (OWASP ASVS V5, NIST SP 800-53 SI-10). This change adds no permission check and no 403
 * case: no code of this repository decides a permission for this operation yet (a later slice, INV-T-27316, adds it).
 * Every other operation answers 501 until the task named for it in `operations.tsv` overrides its method.
 */
@RestController
open class PreIssuanceController(
    private val check: CheckPreIssuance,
) : PreIssuanceApi {
    override fun checkPreIssuance(
        xFlowContext: String,
        draftDocument: DraftDocument,
    ): ResponseEntity<PreIssuanceVerdict> {
        // The four fields the check reads are required by the contract; a draft without one is refused at the edge.
        val type = draftDocument.submissionType
        val gstn = draftDocument.supplierGstn
        val date = draftDocument.invoiceDate
        val amount = draftDocument.gstAmount
        val result =
            when {
                type == null || gstn == null -> {
                    null
                }

                gstn.isBlank() || draftDocument.entityRef.isBlank() || draftDocument.currency.isBlank() -> {
                    null
                }

                date == null || amount == null -> {
                    null
                }

                else -> {
                    check.execute(
                        gstn,
                        draftDocument.entityRef,
                        draftDocument.currency,
                        date,
                        type.value,
                        amount.toPlainString(),
                    )
                }
            }
        return when (result) {
            is PreIssuanceCheck.Verdict -> ResponseEntity.ok(verdictOf(result))
            else -> OverrideHttp.problem(ClosedErrorCode.CONTRACT_VIOLATION)
        }
    }

    private fun verdictOf(decided: PreIssuanceCheck.Verdict): PreIssuanceVerdict {
        val verdict = PreIssuanceVerdict.Verdict.valueOf(decided.verdict.name)
        return PreIssuanceVerdict(
            verdict,
            decided.reasons.map { reasonOf(it, decided.verdict.name) },
            decided.flags.map { PreIssuanceVerdict.Flags.valueOf(it.name) }.ifEmpty { null },
        )
    }

    private fun reasonOf(
        reason: PreIssuanceReason,
        verdictName: String,
    ) = ReasonItem(
        reason.code,
        WRONGFUL_GST_MESSAGE,
        reason.field,
        ReasonItem.Severity.valueOf(verdictName),
        reason.overridable,
    )

    private companion object {
        const val WRONGFUL_GST_MESSAGE = "Wrongful GST charge: the Supplier GSTN is not registered on the invoice date"
    }
}
