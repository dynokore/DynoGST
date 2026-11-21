package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.boundary.BoundaryVersion
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.util.UUID

/**
 * [BoundaryVersionSource] (INV-REQ-1005): the version rows of the listed Doc UUIDs of one tenant in one select in one
 * read transaction, tenant in the WHERE clause as well as the row level security (CS-19). No lock, no write. A state
 * that is no [LifecycleState] name is a defect and fails the call; no state is invented. An empty argument issues no
 * statement.
 */
class JooqBoundaryVersionSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : BoundaryVersionSource {
    private val version = Submission.SUBMISSION_

    override fun versions(
        tenant: TenantId,
        docUuids: Collection<UUID>,
    ): List<BoundaryVersion> {
        if (docUuids.isEmpty()) return emptyList()
        val records =
            runner.inTransaction {
                dsl
                    .select(version.DOC_UUID, version.VERSION, version.ENTITY_REF, version.STATE)
                    .from(version)
                    .where(version.TENANT_ID.eq(tenant.value))
                    .and(version.DOC_UUID.`in`(docUuids.map { it.toString() }))
                    .fetch()
            }
        return records.map {
            val state = checkNotNull(it.get(version.STATE))
            BoundaryVersion(
                docUuid = UUID.fromString(checkNotNull(it.get(version.DOC_UUID))),
                version = checkNotNull(it.get(version.VERSION)),
                entityRef = checkNotNull(it.get(version.ENTITY_REF)),
                state = LifecycleState.entries.firstOrNull { s -> s.name == state } ?: error("unknown state: $state"),
            )
        }
    }
}
