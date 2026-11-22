package com.dynokore.gst.evidence.application.artefact

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** One stored SAMPLE_CHECK artefact (INV-REQ-1036 behavior 2). */
data class SampleCheckRow(
    val artefactType: String,
    val reference: String,
    val hash: String,
    val recordedAt: Instant,
) {
    val position: SampleCheckPosition get() = SampleCheckPosition(recordedAt, reference, hash)
}

/** The sort key of a row: (recordedAt, reference, hash) ascending; unique within a tenant and type. */
data class SampleCheckPosition(
    val recordedAt: Instant,
    val reference: String,
    val hash: String,
)

/** [rows] are at most the limit; [last] is the position of the last row when more rows exist, else null. */
data class SampleCheckPage(
    val rows: List<SampleCheckRow>,
    val last: SampleCheckPosition?,
)

/** Reads the stored SAMPLE_CHECK artefacts of one entity, in the tenant-bound transaction. */
interface SampleCheckReader {
    /** SAMPLE_CHECK rows of [entityRef] of [tenant] after [after], ordered by (recordedAt, reference, hash). */
    fun page(
        tenant: TenantId,
        entityRef: String,
        limit: Int,
        after: SampleCheckPosition?,
    ): SampleCheckPage
}
