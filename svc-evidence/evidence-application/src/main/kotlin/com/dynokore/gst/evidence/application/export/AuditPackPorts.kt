package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.tenancykernel.TenantId

/** The candidates of one content class for one entity and period of a tenant. Reads only; it never writes. */
fun interface AuditPackClassSource {
    fun candidates(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<PackageCandidate>
}

/**
 * The source of each content class of an audit pack (INV-REQ-988). A class with no entry, or a null entry, is
 * unbound: the pack is not built without a source for all six classes.
 */
class AuditPackClassSources(
    private val sources: Map<PackageContentClass, AuditPackClassSource?>,
) {
    fun source(contentClass: PackageContentClass): AuditPackClassSource? = sources[contentClass]
}
