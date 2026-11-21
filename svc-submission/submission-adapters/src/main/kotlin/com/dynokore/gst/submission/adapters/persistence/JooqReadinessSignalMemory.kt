package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.readiness.ReadinessSignalMemory
import com.dynokore.gst.submission.application.readiness.ReadinessSnapshot
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset

/**
 * [ReadinessSignalMemory] over `submission.readiness_signal` (INV-REQ-570). [record] is one idempotent compare-and-set
 * statement in the caller's open transaction and opens none; the tenant is the one bound to the transaction and the
 * time is the injected clock's, never the database's.
 */
class JooqReadinessSignalMemory(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
) : ReadinessSignalMemory {
    override fun record(
        entityRef: String,
        periodRef: String,
        snapshot: ReadinessSnapshot,
    ): Boolean {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        val counts =
            snapshot.countsByState.entries
                .sortedBy { it.key.name }
                .joinToString(prefix = "{", postfix = "}") { "\"${it.key.name}\":${it.value}" }
        val changed =
            dsl
                .resultQuery(
                    "insert into submission.readiness_signal " +
                        "(tenant_id, entity_ref, period_ref, verdict, blocked_count, counts_by_state, signalled_at) " +
                        "values ({0}, {1}, {2}, {3}, {4}, cast({5} as jsonb), {6}) " +
                        "on conflict (tenant_id, entity_ref, period_ref) do update set " +
                        "verdict = excluded.verdict, blocked_count = excluded.blocked_count, " +
                        "counts_by_state = excluded.counts_by_state, signalled_at = excluded.signalled_at " +
                        "where (readiness_signal.verdict, readiness_signal.blocked_count, " +
                        "readiness_signal.counts_by_state) is distinct from " +
                        "(excluded.verdict, excluded.blocked_count, excluded.counts_by_state) returning 1",
                    DSL.`val`(tenants.current().value, SQLDataType.UUID),
                    DSL.`val`(entityRef, SQLDataType.VARCHAR),
                    DSL.`val`(periodRef, SQLDataType.VARCHAR),
                    DSL.`val`(snapshot.verdict.name, SQLDataType.VARCHAR),
                    DSL.`val`(snapshot.blockedCount, SQLDataType.INTEGER),
                    DSL.`val`(counts, SQLDataType.VARCHAR),
                    DSL.`val`(clock.instant().atOffset(ZoneOffset.UTC), SQLDataType.TIMESTAMPWITHTIMEZONE),
                ).fetch()
        return changed.isNotEmpty()
    }
}
