package com.dynokore.gst.reconciliation.application.backfill

import com.dynokore.gst.reconciliation.application.run.MappingEntriesSource
import com.dynokore.gst.reconciliation.application.run.RunDocumentsSource
import com.dynokore.gst.reconciliation.domain.run.BackfillEffect
import com.dynokore.gst.reconciliation.domain.run.ProjectedEffect

/** Query side only (INV-REQ-1090): reads the mapping and the period documents, holds no write port. */
class ProjectBackfillEffect(
    private val entries: MappingEntriesSource,
    private val documents: RunDocumentsSource,
) {
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
        requested: List<String>,
    ): ProjectedEffect =
        BackfillEffect.project(
            entries.entries(tenant, entityRef, periodRef),
            documents.load(tenant, entityRef, periodRef),
            requested,
        )
}
