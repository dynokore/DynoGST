package com.dynokore.gst.rules.application

/** One mapping rule of an exported bundle part or of the active pack; values are compared as given. */
data class MappingBundleRule(
    val internalTaxCode: String,
    val description: String,
    val irasCode: String,
    val rate: String,
    val sourceClass: String,
    val citation: String?,
)

enum class MappingChangeKind { ADDED, REMOVED, CHANGED }

/** One change in the path convention: `rules/<code>/<property>` for CHANGED, `rules/<code>` for ADDED and REMOVED. */
data class MappingPackChange(
    val path: String,
    val change: MappingChangeKind,
    val current: String? = null,
    val proposed: String? = null,
)

sealed interface MappingPackBundleDiffResult {
    data class Changes(
        val changes: List<MappingPackChange>,
    ) : MappingPackBundleDiffResult

    /** A list holds the same internalTaxCode twice; it is refused, never merged. */
    data class DuplicateInternalTaxCode(
        val internalTaxCode: String,
        val side: Side,
    ) : MappingPackBundleDiffResult

    enum class Side { BUNDLE, ACTIVE }
}

/**
 * Pure comparison of an exported mapping-pack part with the active pack (INV-REQ-1128 behaviour 4). It takes values
 * only, no store, so it cannot write. Changes are ordered by path.
 */
class MappingPackBundleDiff {
    fun diff(
        bundle: List<MappingBundleRule>,
        active: List<MappingBundleRule>,
    ): MappingPackBundleDiffResult {
        val bundleSide = MappingPackBundleDiffResult.Side.BUNDLE
        val activeSide = MappingPackBundleDiffResult.Side.ACTIVE
        val proposed = index(bundle) ?: return duplicate(bundle, bundleSide)
        val current = index(active) ?: return duplicate(active, activeSide)
        val changes = mutableListOf<MappingPackChange>()
        for (code in (proposed.keys + current.keys).toSortedSet()) {
            val p = proposed[code]
            val c = current[code]
            when {
                c == null -> changes += MappingPackChange("rules/$code", MappingChangeKind.ADDED)
                p == null -> changes += MappingPackChange("rules/$code", MappingChangeKind.REMOVED)
                else -> changes += propertyChanges(code, c, p)
            }
        }
        return MappingPackBundleDiffResult.Changes(changes)
    }

    private fun index(rules: List<MappingBundleRule>): Map<String, MappingBundleRule>? {
        val map = LinkedHashMap<String, MappingBundleRule>()
        for (r in rules) if (map.put(r.internalTaxCode, r) != null) return null
        return map
    }

    private fun duplicate(
        rules: List<MappingBundleRule>,
        side: MappingPackBundleDiffResult.Side,
    ): MappingPackBundleDiffResult {
        val code =
            rules
                .groupingBy { it.internalTaxCode }
                .eachCount()
                .entries
                .first { it.value > 1 }
                .key
        return MappingPackBundleDiffResult.DuplicateInternalTaxCode(code, side)
    }

    private fun propertyChanges(
        code: String,
        c: MappingBundleRule,
        p: MappingBundleRule,
    ): List<MappingPackChange> =
        listOf(
            Triple("description", c.description, p.description),
            Triple("irasCode", c.irasCode, p.irasCode),
            Triple("rate", c.rate, p.rate),
            Triple("sourceClass", c.sourceClass, p.sourceClass),
            Triple("citation", c.citation, p.citation),
        ).filter { it.second != it.third }
            .map { MappingPackChange("rules/$code/${it.first}", MappingChangeKind.CHANGED, it.second, it.third) }
}
