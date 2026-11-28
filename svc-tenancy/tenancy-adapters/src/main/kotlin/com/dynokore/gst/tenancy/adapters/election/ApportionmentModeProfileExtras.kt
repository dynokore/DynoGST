package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.APPORTIONMENT_MODE_ATTRIBUTE
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.domain.ElectionKind

/**
 * The apportionmentMode entries of an entity (VP-23), one per effective-dated election, read from the one election
 * store: the live profile publishers, the profile read and the replay of EntityProfileChanged carry the same entries
 * (INV-AC-26475).
 */
class ApportionmentModeProfileExtras(
    private val elections: ElectionPolicyStore,
) : ProfileExtras {
    override fun entries(entityRef: String): List<ProfileEntry> =
        elections
            .history(entityRef, ElectionKind.APPORTIONMENT_MODE)
            .map { ProfileEntry(APPORTIONMENT_MODE_ATTRIBUTE, it.value, it.effectiveFrom) }
}
