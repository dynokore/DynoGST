package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision
import com.dynokore.gst.submission.domain.migration.PriorSolutionGuard
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/**
 * Raises the SUB-017 hold of one document version once (INV-REQ-1110, INV-Q-6887). It stores one row and transmits
 * nothing; it changes no lifecycle state. A repeat for the same (tenant, doc UUID, version) inserts none and returns
 * the first row. A decision whose code is not SUB-017 is refused and raises nothing.
 */
class RaisePriorSolutionHold(
    private val store: PriorSolutionHoldStore,
    private val clock: Clock,
) {
    fun execute(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        entityRef: String,
        decision: PriorSolutionDecision.HoldForCorrection,
    ): PriorSolutionHold {
        require(decision.code == PriorSolutionGuard.SUB_017) { "not a SUB-017 decision: ${decision.code}" }
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(version >= 1) { "version must be at least 1: $version" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        return store.raise(
            PriorSolutionHold(
                tenant,
                docUuid,
                version,
                entityRef,
                decision.reason,
                decision.code,
                clock.instant(),
            ),
        )
    }
}
