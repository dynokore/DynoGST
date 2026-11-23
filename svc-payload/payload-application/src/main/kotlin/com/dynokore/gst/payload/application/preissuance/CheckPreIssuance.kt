package com.dynokore.gst.payload.application.preissuance

import com.dynokore.gst.payload.domain.PreIssuanceFlag
import com.dynokore.gst.payload.domain.PreIssuanceInputError
import com.dynokore.gst.payload.domain.PreIssuanceOutcome
import com.dynokore.gst.payload.domain.PreIssuanceReason
import com.dynokore.gst.payload.domain.PreIssuanceRegisterStatus
import com.dynokore.gst.payload.domain.PreIssuanceVerdict
import com.dynokore.gst.payload.domain.PreIssuanceVerdictDecision
import java.time.LocalDate

/** The result of the pre-issuance check (INV-REQ-475). */
sealed interface PreIssuanceCheck {
    data class Verdict(
        val verdict: PreIssuanceVerdict,
        val reasons: List<PreIssuanceReason>,
        val flags: Set<PreIssuanceFlag>,
    ) : PreIssuanceCheck

    data class Refused(
        val error: PreIssuanceInputError,
    ) : PreIssuanceCheck
}

/**
 * INV-REQ-475: the System's wrongful GST charge check (Observe mode, the System's default selection). The supplier GSTN
 * goes to the register unchanged; a register that cannot answer, or answers an unlisted status, is decided as
 * REGISTER_UNAVAILABLE (fail safe, OWASP ASVS V5, NIST SP 800-53 SI-10). Stores nothing, holds no clock, checks no
 * role.
 */
class CheckPreIssuance(
    private val register: RegisterStatusSource,
) {
    @Suppress("LongParameterList")
    fun execute(
        supplierGstn: String,
        entityRef: String,
        currency: String,
        invoiceDate: LocalDate,
        submissionType: String?,
        gstAmount: String?,
    ): PreIssuanceCheck {
        // Precondition of the caller (design by contract): the edge refuses blank keys; no trim, no default, no catch.
        require(supplierGstn.isNotBlank()) { "supplierGstn must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(currency.isNotBlank()) { "currency must not be blank" }
        // Refuse an incomplete or malformed draft before the register is asked.
        val early = decide(submissionType, PreIssuanceRegisterStatus.REGISTERED.name, gstAmount)
        if (early is PreIssuanceOutcome.Refused) return PreIssuanceCheck.Refused(early.error)

        val status =
            when (val answer = register.lookup(RegisterQuery(supplierGstn, entityRef, currency, invoiceDate))) {
                is RegisterLookup.Answered -> answer.status
                RegisterLookup.Unavailable -> null
            }
        val known =
            PreIssuanceRegisterStatus.entries.any {
                it.name == status &&
                    it != PreIssuanceRegisterStatus.REGISTER_UNAVAILABLE
            }
        val effective = if (known) status else PreIssuanceRegisterStatus.REGISTER_UNAVAILABLE.name
        return when (val outcome = decide(submissionType, effective, gstAmount)) {
            is PreIssuanceOutcome.Decided -> PreIssuanceCheck.Verdict(outcome.verdict, outcome.reasons, outcome.flags)
            is PreIssuanceOutcome.Refused -> PreIssuanceCheck.Refused(outcome.error)
        }
    }

    private fun decide(
        submissionType: String?,
        status: String?,
        gstAmount: String?,
    ) = PreIssuanceVerdictDecision.decideRaw(submissionType, status, gstAmount, "SYSTEM_EVERY_PATH", false)
}
