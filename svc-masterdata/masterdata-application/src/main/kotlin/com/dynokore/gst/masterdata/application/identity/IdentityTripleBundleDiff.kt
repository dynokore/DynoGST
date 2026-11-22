package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.TripleVersion

/** How one identity triple differs between a bundle part and the active triples (contract: `ConfigurationChange`). */
enum class IdentityTripleChangeKind { ADDED, REMOVED, CHANGED }

/**
 * One change in the contract path convention. [currentValue] and [proposedValue] are set for
 * [IdentityTripleChangeKind.CHANGED] only, as the text of the one property that differs; an ADDED or REMOVED
 * triple carries neither.
 */
data class IdentityTripleChange(
    val path: String,
    val kind: IdentityTripleChangeKind,
    val currentValue: String? = null,
    val proposedValue: String? = null,
)

/** Which input held the same (entity, constitution) twice. */
enum class DiffSide { PART, ACTIVE }

/** The outcome of the diff: the changes, or a typed refusal when a side has two triples for one key. */
sealed interface IdentityTripleBundleDiffResult {
    data class Diff(
        val changes: List<IdentityTripleChange>,
    ) : IdentityTripleBundleDiffResult

    /** [side] holds more than one triple for the same entity and constitution, so no unambiguous comparison exists. */
    data class AmbiguousTriples(
        val side: DiffSide,
        val entityRef: String,
        val constitution: String,
    ) : IdentityTripleBundleDiffResult
}

/**
 * INV-REQ-1128: the pure diff of the identity-triple part of an exported bundle against the active triples (the
 * owning service computes it; console-bff stores no bundle). It takes the two lists as values and no store, reads and
 * writes nothing. Triples are matched by (entity, constitution); a matched pair lists one CHANGED entry for each of
 * senderId, supplierId, supplierGstn and effectiveFrom that differs; an equal pair lists nothing.
 */
object IdentityTripleBundleDiff {
    fun diff(
        part: List<TripleVersion>,
        active: List<TripleVersion>,
    ): IdentityTripleBundleDiffResult {
        val proposed = index(part, DiffSide.PART)
        val current = index(active, DiffSide.ACTIVE)
        return when {
            proposed is Indexed.Ambiguous -> {
                proposed.refusal
            }

            current is Indexed.Ambiguous -> {
                current.refusal
            }

            else -> {
                val diff = compare((proposed as Indexed.Ok).byKey, (current as Indexed.Ok).byKey)
                IdentityTripleBundleDiffResult.Diff(diff)
            }
        }
    }

    private fun compare(
        proposedByKey: Map<Pair<String, String>, TripleVersion>,
        currentByKey: Map<Pair<String, String>, TripleVersion>,
    ): List<IdentityTripleChange> {
        val changes = mutableListOf<IdentityTripleChange>()
        for ((key, version) in proposedByKey) {
            val before = currentByKey[key]
            if (before == null) {
                changes += IdentityTripleChange(basePath(version), IdentityTripleChangeKind.ADDED)
            } else {
                changes += changed(before, version)
            }
        }
        for ((key, version) in currentByKey) {
            if (key !in proposedByKey) {
                changes += IdentityTripleChange(basePath(version), IdentityTripleChangeKind.REMOVED)
            }
        }
        return changes.sortedBy { it.path }
    }

    private fun changed(
        before: TripleVersion,
        after: TripleVersion,
    ): List<IdentityTripleChange> {
        val base = basePath(after)
        val pairs =
            listOf(
                Triple("senderId", before.triple.senderId, after.triple.senderId),
                Triple("supplierId", before.triple.supplierId, after.triple.supplierId),
                Triple("supplierGstn", before.triple.supplierGstn, after.triple.supplierGstn),
                Triple("effectiveFrom", before.effectiveFrom.toString(), after.effectiveFrom.toString()),
            )
        return pairs.filter { it.second != it.third }.map {
            IdentityTripleChange("$base/${it.first}", IdentityTripleChangeKind.CHANGED, it.second, it.third)
        }
    }

    private fun basePath(version: TripleVersion): String =
        "identity-triple/${version.entity.value}/${version.constitution.code}"

    private fun index(
        versions: List<TripleVersion>,
        side: DiffSide,
    ): Indexed {
        val byKey = LinkedHashMap<Pair<String, String>, TripleVersion>()
        for (version in versions) {
            val key = version.entity.value to version.constitution.code
            if (byKey.put(key, version) != null) {
                return Indexed.Ambiguous(IdentityTripleBundleDiffResult.AmbiguousTriples(side, key.first, key.second))
            }
        }
        return Indexed.Ok(byKey)
    }

    private sealed interface Indexed {
        data class Ok(
            val byKey: Map<Pair<String, String>, TripleVersion>,
        ) : Indexed

        data class Ambiguous(
            val refusal: IdentityTripleBundleDiffResult.AmbiguousTriples,
        ) : Indexed
    }
}
