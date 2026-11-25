package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.application.document.SourceLink
import com.dynokore.gst.canonical.domain.source.SourceKey
import java.util.UUID

/** The `source_event` rows that the RECORDED links of test-written versions need. */
object SourceLinks {
    /** Commits a `source_event` row of its own for [docUuid] in [tenant] and returns the link to it. */
    fun recorded(
        tenant: UUID,
        docUuid: UUID,
    ): SourceLink.Recorded {
        val key = SourceKey(tenant, "test-seed", UUID.randomUUID().toString(), 1)
        seed(key, docUuid)
        return SourceLink.Recorded(key)
    }

    /** Commits the `source_event` row of [key], bound to [docUuid]. */
    fun seed(
        key: SourceKey,
        docUuid: UUID,
    ) {
        DocumentSql.rows(
            "insert into canonical.source_event (tenant_id, source_system, source_key, source_revision, doc_uuid, " +
                "result) values (?, ?, ?, ?, ?, '{}'::jsonb) returning 1",
            key.tenantId,
            key.sourceSystem,
            key.sourceKey,
            key.sourceRevision,
            docUuid,
        )
    }
}
