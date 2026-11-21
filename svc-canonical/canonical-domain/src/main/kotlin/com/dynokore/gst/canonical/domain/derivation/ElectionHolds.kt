package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.dating.Direction

/**
 * The election holds of INV-REQ-987 (VP-23, VP-24): a purchase without an apportionment-mode election holds with PER-012,
 * a foreign-currency document without an exchange-rate-source election holds with PAY-016. Pure: no clock, no state;
 * the hold is decided by [DerivationGate] (INV-REQ-479).
 */
object ElectionHolds {
    const val APPORTIONMENT_HOLD_CODE = "PER-012"
    const val RATE_SOURCE_HOLD_CODE = "PAY-016"
    const val REFERENCE_CURRENCY = "SGD"

    fun decide(
        direction: Direction,
        currency: String?,
        apportionmentElected: Boolean,
        rateSourceElected: Boolean,
    ): Map<DerivationStep, String> {
        val holds = linkedMapOf<DerivationStep, String>()
        if (direction == Direction.PURCHASE && !apportionmentElected) {
            holds[DerivationStep.DOCUMENT_SCOPE_VERDICT] = APPORTIONMENT_HOLD_CODE
        }
        val foreign = currency != null && currency.isNotBlank() && currency != REFERENCE_CURRENCY
        if (foreign && !rateSourceElected) {
            holds[DerivationStep.SUBTOTALS] = RATE_SOURCE_HOLD_CODE
        }
        return holds
    }
}
