package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.preissuance.AuthorizedCheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.AuthorizedPreIssuance
import com.dynokore.gst.payload.application.preissuance.PreIssuanceCaller
import com.dynokore.gst.payload.application.preissuance.PreIssuanceCheck
import com.dynokore.gst.payload.contract.api.PreIssuanceApi
import com.dynokore.gst.payload.contract.model.DraftDocument
import com.dynokore.gst.payload.contract.model.PreIssuanceVerdict
import com.dynokore.gst.payload.contract.model.ReasonItem
import com.dynokore.gst.payload.domain.PreIssuanceReason
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * The pre-issuance operations of the payload contract (TPL-08). `checkPreIssuance` (INV-REQ-475) is stateless and
 * side-effect free (INV-REQ-325): no transaction, no store, no audit write. The contract requires `submissionType`,
 * `supplierGstn`, `invoiceDate` and `gstAmount`, so the generated type carries them non-null and a draft without one
 * is refused when it is read, before this controller runs. A draft the use case refuses (an amount that is not a
 * two-place decimal) or a blank `supplierGstn`, `entityRef` or `currency` is the API-20 envelope SRT-001 (the closed
 * code CONTRACT_VIOLATION), decided before any register lookup, so the use case's precondition is never reached
 * (OWASP ASVS V5, NIST SP 800-53 SI-10). This class decides no permission: the permission is decided per request by
 * the port of the use case ([AuthorizedCheckPreIssuance]), before any register lookup, and a refusal is the API-20
 * envelope SRT-004 (ACCESS_DENIED). The caller is the verified machine `Principal` of [MachineTokenFilter] passed
 * through unchanged when it is of kind MACHINE with a tenant, else the bound end user (with a tenant) as a HUMAN
 * `Principal`; anything else is UNAUTHENTICATED, never HUMAN (OWASP ASVS V4, deny by default, INV-REQ-957).
 * Every other operation answers 501 until the task named for it in `operations.tsv` overrides its method.
 */
@RestController
open class PreIssuanceController(
    private val check: AuthorizedCheckPreIssuance,
    private val http: ObjectProvider<HttpServletRequest>,
) : PreIssuanceApi {
    override fun checkPreIssuance(
        xFlowContext: String,
        draftDocument: DraftDocument,
    ): ResponseEntity<PreIssuanceVerdict> {
        val caller = callerOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        return answer(caller, draftDocument)
    }

    private fun answer(
        caller: PreIssuanceCaller,
        draftDocument: DraftDocument,
    ): ResponseEntity<PreIssuanceVerdict> {
        val gstn = draftDocument.supplierGstn
        if (gstn.isBlank() || draftDocument.entityRef.isBlank() || draftDocument.currency.isBlank()) {
            return OverrideHttp.problem(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        val result =
            check.execute(
                caller,
                gstn,
                draftDocument.entityRef,
                draftDocument.currency,
                draftDocument.invoiceDate,
                draftDocument.submissionType.value,
                draftDocument.gstAmount.toPlainString(),
            )
        return when (result) {
            AuthorizedPreIssuance.Forbidden -> {
                OverrideHttp.problem(ClosedErrorCode.ACCESS_DENIED)
            }

            is AuthorizedPreIssuance.Checked -> {
                when (val checked = result.check) {
                    is PreIssuanceCheck.Verdict -> ResponseEntity.ok(verdictOf(checked))
                    is PreIssuanceCheck.Refused -> OverrideHttp.problem(ClosedErrorCode.CONTRACT_VIOLATION)
                }
            }
        }
    }

    /** The machine attribute first, as it is; else the end user as HUMAN; each only with a tenant. */
    private fun callerOrNull(): PreIssuanceCaller? {
        val request = http.getObject()
        val machine = request.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE)
        if (machine != null) {
            return (machine as? Principal)
                ?.takeIf { it.kind == PrincipalKind.MACHINE && it.tenant != null }
                ?.let { PreIssuanceCaller(it) }
        }
        val endUser = request.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        return endUser
            ?.takeIf { it.tenant != null }
            ?.let { PreIssuanceCaller(Principal(it.actor, PrincipalKind.HUMAN)) }
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
