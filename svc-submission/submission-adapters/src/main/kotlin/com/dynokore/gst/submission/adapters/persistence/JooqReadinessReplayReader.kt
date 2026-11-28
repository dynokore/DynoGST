package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessReplayTenants
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessSignal
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** One stored readiness signal as the replay reads it (INV-T-29143). */
data class StoredReadinessSignal(
    val entityRef: String,
    val periodRef: String,
    val verdict: String,
    val blockedCount: Int,
    val countsByStateJson: String,
    val signalledAt: OffsetDateTime,
)

/**
 * Reads `submission.readiness_signal` for the replay of ReadinessChanged (INV-REQ-1205 behavior 4, INV-AC-29140). The
 * tenant list is the sys-owned function [ReadinessReplayTenants], called outside a tenant transaction. [page] runs in
 * the caller's tenant transaction (row level security binds the tenant), orders by the primary key below the tenant
 * (keyset), and locks the rows it returns with `FOR SHARE` until that transaction ends, so a live change that follows
 * commits after the replayed state. It writes nothing and never reads the outbox or the event log.
 */
class JooqReadinessReplayReader(
    private val dsl: DSLContext,
) {
    fun tenants(): List<TenantId> {
        val fn = ReadinessReplayTenants.READINESS_REPLAY_TENANTS.call()
        return dsl
            .select(fn.REPLAY_TENANT)
            .from(fn)
            .fetch()
            .map { TenantId(checkNotNull(it.value1())) }
    }

    fun page(
        scope: ReplayScope?,
        after: Pair<String, String>?,
        count: Int,
    ): List<StoredReadinessSignal> {
        val t = ReadinessSignal.READINESS_SIGNAL
        return dsl
            .select(t.ENTITY_REF, t.PERIOD_REF, t.VERDICT, t.BLOCKED_COUNT, t.COUNTS_BY_STATE, t.SIGNALLED_AT)
            .from(t)
            .where(inScope(t, scope), afterKey(t, after))
            .orderBy(t.ENTITY_REF, t.PERIOD_REF)
            .limit(count)
            .forShare()
            .fetch()
            .map {
                StoredReadinessSignal(
                    checkNotNull(it[t.ENTITY_REF]),
                    checkNotNull(it[t.PERIOD_REF]),
                    checkNotNull(it[t.VERDICT]),
                    checkNotNull(it[t.BLOCKED_COUNT]),
                    checkNotNull(it[t.COUNTS_BY_STATE]).data(),
                    checkNotNull(it[t.SIGNALLED_AT]),
                )
            }
    }

    private fun afterKey(
        t: ReadinessSignal,
        after: Pair<String, String>?,
    ): Condition =
        after?.let { (entity, period) ->
            DSL.row(t.ENTITY_REF, t.PERIOD_REF).gt(DSL.row(entity, period))
        } ?: DSL.noCondition()

    private fun inScope(
        t: ReadinessSignal,
        scope: ReplayScope?,
    ): Condition =
        when (scope) {
            null -> DSL.noCondition()
            is ReplayScope.Tenant -> t.TENANT_ID.eq(scope.tenant.value)
            is ReplayScope.Entity -> t.TENANT_ID.eq(scope.tenant.value).and(t.ENTITY_REF.eq(scope.entity.value))
            is ReplayScope.Period ->
                t.SIGNALLED_AT
                    .ge(scope.from.atOffset(ZoneOffset.UTC))
                    .and(t.SIGNALLED_AT.lt(scope.to.atOffset(ZoneOffset.UTC)))
                    .and(scope.tenant?.let { t.TENANT_ID.eq(it.value) } ?: DSL.noCondition())
        }
}
