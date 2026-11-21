package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessSignal
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
        val json =
            snapshot.countsByState.entries
                .sortedBy { it.key.name }
                .joinToString(prefix = "{", postfix = "}") { "\"${it.key.name}\":${it.value}" }
        val t = ReadinessSignal.READINESS_SIGNAL
        val counts = DSL.`val`(json, SQLDataType.VARCHAR).cast(SQLDataType.JSONB)
        val changed =
            dsl
                .insertInto(t)
                .set(t.TENANT_ID, tenants.current().value)
                .set(t.ENTITY_REF, entityRef)
                .set(t.PERIOD_REF, periodRef)
                .set(t.VERDICT, snapshot.verdict.name)
                .set(t.BLOCKED_COUNT, snapshot.blockedCount)
                .set(t.COUNTS_BY_STATE, counts)
                .set(t.SIGNALLED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .onConflict(t.TENANT_ID, t.ENTITY_REF, t.PERIOD_REF)
                .doUpdate()
                .set(t.VERDICT, DSL.excluded(t.VERDICT))
                .set(t.BLOCKED_COUNT, DSL.excluded(t.BLOCKED_COUNT))
                .set(t.COUNTS_BY_STATE, DSL.excluded(t.COUNTS_BY_STATE))
                .set(t.SIGNALLED_AT, DSL.excluded(t.SIGNALLED_AT))
                .where(
                    DSL
                        .row(t.VERDICT, t.BLOCKED_COUNT, t.COUNTS_BY_STATE)
                        .isDistinctFrom(
                            DSL.row(
                                DSL.excluded(t.VERDICT),
                                DSL.excluded(t.BLOCKED_COUNT),
                                DSL.excluded(t.COUNTS_BY_STATE),
                            ),
                        ),
                ).returningResult(DSL.inline(1))
                .fetch()
        return changed.isNotEmpty()
    }
}
