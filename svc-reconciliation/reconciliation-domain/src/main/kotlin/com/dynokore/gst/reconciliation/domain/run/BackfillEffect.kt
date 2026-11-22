package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.BoxAggregator
import com.dynokore.gst.reconciliation.domain.boxes.DocumentLine
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import java.math.BigDecimal

/** Why a requested document adds nothing to the projection. */
enum class ExclusionReason { ALREADY_ACKNOWLEDGED, NOT_FOUND, NOT_RECONCILABLE }

data class BoxEffect(
    val box: String,
    val projectedChange: BigDecimal,
)

data class ExcludedDocument(
    val docUuid: String,
    val reason: ExclusionReason,
)

data class ProjectedEffect(
    val boxes: List<BoxEffect>,
    val unmapped: List<UnmappedLine>,
    val excluded: List<ExcludedDocument>,
)

/**
 * Read-only projection of the change per return box that backfilling the requested documents would add: the
 * contributions of the unsubmitted current versions, the same arithmetic as [ReconciliationRunner.run]. Pure.
 */
object BackfillEffect {
    private const val SCALE = 2

    fun project(
        entries: List<MappingEntry>,
        documents: List<RunDocument>,
        requested: List<String>,
    ): ProjectedEffect {
        val byUuid = documents.associateBy { it.docUuid }
        val excluded = mutableListOf<ExcludedDocument>()
        val lines = mutableListOf<DocumentLine>()
        for (uuid in requested.distinct().sorted()) {
            val doc = byUuid[uuid]
            if (doc == null) {
                excluded += ExcludedDocument(uuid, ExclusionReason.NOT_FOUND)
                continue
            }
            if (doc.versions.any { it.acknowledged }) {
                excluded += ExcludedDocument(uuid, ExclusionReason.ALREADY_ACKNOWLEDGED)
                continue
            }
            val current = doc.versions.maxByOrNull { it.version } ?: continue
            if (current.allocation is SgdAllocation.NotReconcilable) {
                excluded += ExcludedDocument(uuid, ExclusionReason.NOT_RECONCILABLE)
                continue
            }
            lines += ReconciliationRunner.linesOfVersion(uuid, current.version, current)
        }
        val boxes =
            BoxAggregator
                .contributions(entries, lines)
                .groupBy({ it.box }, { it.amount })
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO.setScale(SCALE)) { a, b -> a + b } }
                .filterValues { it.signum() != 0 }
                .toSortedMap()
                .map { (box, sum) -> BoxEffect(box, sum) }
        return ProjectedEffect(
            boxes = boxes,
            unmapped = BoxAggregator.aggregate(entries, lines).unmappedLines,
            excluded = excluded.sortedBy { it.docUuid },
        )
    }
}
