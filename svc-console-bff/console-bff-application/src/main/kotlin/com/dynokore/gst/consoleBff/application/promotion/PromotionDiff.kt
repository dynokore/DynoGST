package com.dynokore.gst.consoleBff.application.promotion

import com.dynokore.gst.consoleBff.application.override.OwnerReply

/** One change of a domain's configuration, as the owning service computes it (ConfigurationChange of the contract). */
data class ConfigurationChange(
    val path: String,
    val change: String,
    val current: String?,
    val proposed: String?,
)

/** The approval path that remains for a domain after promotion (INV-REQ-772, INV-REQ-1007). */
enum class ApprovalPath {
    MAPPING_DRY_RUN_AND_APPROVAL,
    IDENTITY_TRIPLE_DUAL_CONTROL,
}

/** The two promotable domains; [wire] is the `domain` value of previewConfigurationPromotion. */
enum class PromotionDomain(
    val wire: String,
    val approvalPath: ApprovalPath,
) {
    MAPPING_PACK("mapping-pack", ApprovalPath.MAPPING_DRY_RUN_AND_APPROVAL),
    IDENTITY_TRIPLE("identity-triple", ApprovalPath.IDENTITY_TRIPLE_DUAL_CONTROL),
    ;

    companion object {
        fun fromWire(value: String): PromotionDomain? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Port to one owning service: reads the exported bundle part of the domain and returns the owning service's diff of
 * it against its active production configuration. Read-only; implemented by the wiring task.
 */
fun interface PromotionDiffPort {
    fun diff(bundleId: String): OwnerReply<List<ConfigurationChange>>
}

/** The merged diff of one domain. */
data class ConfigurationDiff(
    val bundleId: String,
    val domain: String,
    val changes: List<ConfigurationChange>,
    val approvalPath: ApprovalPath,
)

sealed interface PromotionDiffResult {
    data class Diff(
        val diff: ConfigurationDiff,
    ) : PromotionDiffResult

    /** The domain value is none of the two promotable domains; no owning service was called. */
    data class UnknownDomain(
        val domain: String,
    ) : PromotionDiffResult

    /** The owning service did not answer with a diff; never an empty diff. */
    data class OwnerFailed(
        val failure: OwnerReply<Nothing>,
    ) : PromotionDiffResult
}

/** Merges the owning service's diff of one domain with its approval path; holds no store. */
class PromotionDiff(
    private val mappingPack: PromotionDiffPort,
    private val identityTriple: PromotionDiffPort,
) {
    fun diff(
        bundleId: String,
        domain: String,
    ): PromotionDiffResult {
        val d = PromotionDomain.fromWire(domain) ?: return PromotionDiffResult.UnknownDomain(domain)
        val port =
            when (d) {
                PromotionDomain.MAPPING_PACK -> mappingPack
                PromotionDomain.IDENTITY_TRIPLE -> identityTriple
            }
        return when (val reply = port.diff(bundleId)) {
            is OwnerReply.Ok -> {
                PromotionDiffResult.Diff(
                    ConfigurationDiff(bundleId, d.wire, reply.value, d.approvalPath),
                )
            }

            is OwnerReply.Refused -> {
                PromotionDiffResult.OwnerFailed(reply)
            }

            is OwnerReply.Unavailable -> {
                PromotionDiffResult.OwnerFailed(reply)
            }
        }
    }
}
