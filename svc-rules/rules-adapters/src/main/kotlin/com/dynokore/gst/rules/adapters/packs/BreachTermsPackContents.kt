package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.resolution.PackTypes

/** The two statutory terms of a `breach-terms` pack, in whole hours, with the source of the text they come from. */
data class BreachTerms(
    val assessmentWithinHours: Int,
    val notificationWithinHours: Int,
    val sourceReference: String,
)

/**
 * Decodes a `breach-terms` pack (INV-REQ-1385; operator-maintained, INV-REQ-1343). Both terms are whole
 * numbers of 1 or more and `sourceReference` is non-empty text. A missing, zero, negative, fractional or non-numeric
 * term, or an empty reference, fails naming the member. Nothing is defaulted or partly accepted; a member the schema
 * does not name is ignored (API-41).
 */
object BreachTermsPackContents {
    fun decode(contents: ByteArray): BreachTerms {
        val root = ContentsNode.root(PackTypes.BREACH_TERMS.value, contents)
        val assessment = term(root, "assessmentWithinHours")
        val notification = term(root, "notificationWithinHours")
        return BreachTerms(assessment, notification, root.text("sourceReference", min = 1))
    }

    private fun term(
        root: ContentsNode,
        name: String,
    ): Int {
        val value = root.member(name) ?: root.fail(name, "is required")
        if (!value.isIntegralNumber || !value.canConvertToInt()) root.fail(name, "must be a whole number of hours")
        val hours = value.asInt()
        if (hours < 1) root.fail(name, "must be 1 or more: $hours")
        return hours
    }
}
