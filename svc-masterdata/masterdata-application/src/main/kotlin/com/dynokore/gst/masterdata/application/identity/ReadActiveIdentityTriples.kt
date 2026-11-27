package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.application.registry.businessDate
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.gst.masterdata.domain.identity.inForceOn
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/**
 * The active identity triples of the bound tenant (INV-REQ-1128): for each pair of entity and constitution, the version
 * in force on the Singapore business date. A pair with no version in force is left out. The result is ordered by entity
 * reference, then constitution code, and has no size limit.
 */
class ReadActiveIdentityTriples(
    private val tx: TransactionRunner,
    private val triples: TripleRepository,
    private val clock: Clock,
) {
    fun handle(): List<TripleVersion> =
        tx.inTransaction {
            val today = clock.businessDate()
            triples
                .allVersions()
                .groupBy { it.entity to it.constitution }
                .values
                .mapNotNull { it.inForceOn(today) }
                .sortedWith(compareBy({ it.entity.value }, { it.constitution.code }))
        }
}
