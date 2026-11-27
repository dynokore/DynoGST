package com.dynokore.gst.rules.domain.resolution

import com.dynokore.platform.rulepackkernel.PackType

/**
 * The pack types of packs.yaml, spelled as the rules contract spells a pack type (upper snake case, `SCOPE` in the
 * contract examples). The kernel keeps [PackType] opaque (ARC-16); the product vocabulary lives here.
 * The one exception to the upper snake spelling is [BREACH_TERMS]: its wire value is `breach-terms`, as the published
 * breach terms contents schema spells it, and BREACH_TERMS is only the code name (INV-AC-29341).
 */
object PackTypes {
    val SCOPE = PackType("SCOPE")
    val CODE_CATALOGUE = PackType("CODE_CATALOGUE")
    val MDE = PackType("MDE")
    val SCHEMATRON = PackType("SCHEMATRON")
    val SEVERITY_OVERRIDE = PackType("SEVERITY_OVERRIDE")
    val SCENARIO = PackType("SCENARIO")
    val CALENDAR = PackType("CALENDAR")
    val MAPPING = PackType("MAPPING")
    val RETURN_MAPPING = PackType("RETURN_MAPPING")
    val ENDPOINT_ACCEPTANCE = PackType("ENDPOINT_ACCEPTANCE")
    val BREACH_TERMS = PackType("breach-terms")

    /**
     * INV-REQ-770 behaviour 3 (INV-AC-1782): the rate and the code catalogue resolve at the preceding document's date
     * for a linked credit note. A rate is part of the code catalogue's contents (packs.yaml), so this is one pack type.
     */
    val RESOLVED_AT_PRECEDING_DATE: Set<PackType> = setOf(CODE_CATALOGUE)
}
