package com.dynokore.gst.consoleBff.adapters.portfolio

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.PORTFOLIO_PERIOD
import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.PORTFOLIO_READINESS
import com.dynokore.gst.consoleBff.application.portfolio.PortfolioStore
import com.dynokore.gst.consoleBff.application.portfolio.StoredFact
import com.dynokore.gst.consoleBff.application.portfolio.StoredPeriod
import com.dynokore.gst.consoleBff.application.portfolio.StoredReadiness
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodState
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * `console_bff.portfolio_period` and `portfolio_readiness` through the jOOQ DSL (TPL-11). Isolation is the forced row
 * level security under the caller's signed tenant binding; every statement also names the tenant (defence in depth,
 * NIST SP 800-53 AC-3). A readiness write is ordered by its observation time (event-carried state transfer).
 */
class JooqPortfolioStore(
    private val dsl: DSLContext,
) : PortfolioStore {
    override fun putPeriods(
        tenantId: String,
        rows: List<StoredPeriod>,
    ) {
        val tenant = UUID.fromString(tenantId)
        for (r in rows) {
            dsl
                .insertInto(PORTFOLIO_PERIOD)
                .set(PORTFOLIO_PERIOD.TENANT_ID, tenant)
                .set(PORTFOLIO_PERIOD.ENTITY_REF, r.entityRef)
                .set(PORTFOLIO_PERIOD.PERIOD_REF, r.periodRef)
                .set(PORTFOLIO_PERIOD.STATE, r.state.name)
                .set(PORTFOLIO_PERIOD.FILING_DUE_DATE, r.filingDueDate)
                .set(PORTFOLIO_PERIOD.FILING_DATE, r.filingDate)
                .set(PORTFOLIO_PERIOD.EXTENSION_GRANTED, r.extensionGranted)
                .onConflict(PORTFOLIO_PERIOD.TENANT_ID, PORTFOLIO_PERIOD.ENTITY_REF, PORTFOLIO_PERIOD.PERIOD_REF)
                .doUpdate()
                .set(PORTFOLIO_PERIOD.STATE, DSL.excluded(PORTFOLIO_PERIOD.STATE))
                .set(PORTFOLIO_PERIOD.FILING_DUE_DATE, DSL.excluded(PORTFOLIO_PERIOD.FILING_DUE_DATE))
                .set(PORTFOLIO_PERIOD.FILING_DATE, DSL.excluded(PORTFOLIO_PERIOD.FILING_DATE))
                .set(PORTFOLIO_PERIOD.EXTENSION_GRANTED, DSL.excluded(PORTFOLIO_PERIOD.EXTENSION_GRANTED))
                .execute()
        }
    }

    override fun putReadiness(
        tenantId: String,
        row: StoredReadiness,
        observedAt: Instant,
    ): Boolean {
        val at = observedAt.atOffset(ZoneOffset.UTC)
        return dsl
            .insertInto(PORTFOLIO_READINESS)
            .set(PORTFOLIO_READINESS.TENANT_ID, UUID.fromString(tenantId))
            .set(PORTFOLIO_READINESS.ENTITY_REF, row.entityRef)
            .set(PORTFOLIO_READINESS.PERIOD_REF, row.periodRef)
            .set(PORTFOLIO_READINESS.VERDICT, row.verdict.name)
            .set(PORTFOLIO_READINESS.BLOCKED_COUNT, row.blockedCount)
            .set(PORTFOLIO_READINESS.OBSERVED_AT, at)
            .onConflict(PORTFOLIO_READINESS.TENANT_ID, PORTFOLIO_READINESS.ENTITY_REF, PORTFOLIO_READINESS.PERIOD_REF)
            .doUpdate()
            .set(PORTFOLIO_READINESS.VERDICT, DSL.excluded(PORTFOLIO_READINESS.VERDICT))
            .set(PORTFOLIO_READINESS.BLOCKED_COUNT, DSL.excluded(PORTFOLIO_READINESS.BLOCKED_COUNT))
            .set(PORTFOLIO_READINESS.OBSERVED_AT, DSL.excluded(PORTFOLIO_READINESS.OBSERVED_AT))
            .where(DSL.excluded(PORTFOLIO_READINESS.OBSERVED_AT).gt(PORTFOLIO_READINESS.OBSERVED_AT))
            .execute() == 1
    }

    override fun facts(
        tenantId: String,
        entityRefs: Set<String>,
    ): List<StoredFact> {
        if (entityRefs.isEmpty()) return emptyList()
        val tenant = UUID.fromString(tenantId)
        return dsl
            .select()
            .from(PORTFOLIO_PERIOD)
            .leftJoin(PORTFOLIO_READINESS)
            .on(PORTFOLIO_READINESS.TENANT_ID.eq(PORTFOLIO_PERIOD.TENANT_ID))
            .and(PORTFOLIO_READINESS.ENTITY_REF.eq(PORTFOLIO_PERIOD.ENTITY_REF))
            .and(PORTFOLIO_READINESS.PERIOD_REF.eq(PORTFOLIO_PERIOD.PERIOD_REF))
            .where(PORTFOLIO_PERIOD.TENANT_ID.eq(tenant))
            .and(PORTFOLIO_PERIOD.ENTITY_REF.`in`(entityRefs))
            .orderBy(PORTFOLIO_PERIOD.ENTITY_REF, PORTFOLIO_PERIOD.PERIOD_REF)
            .fetch()
            .map { r ->
                val entity = checkNotNull(r.get(PORTFOLIO_PERIOD.ENTITY_REF))
                val period = checkNotNull(r.get(PORTFOLIO_PERIOD.PERIOD_REF))
                val verdict = r.get(PORTFOLIO_READINESS.VERDICT)
                StoredFact(
                    StoredPeriod(
                        entity,
                        period,
                        PeriodState.valueOf(checkNotNull(r.get(PORTFOLIO_PERIOD.STATE))),
                        checkNotNull(r.get(PORTFOLIO_PERIOD.FILING_DUE_DATE)),
                        r.get(PORTFOLIO_PERIOD.FILING_DATE),
                        checkNotNull(r.get(PORTFOLIO_PERIOD.EXTENSION_GRANTED)),
                    ),
                    verdict?.let {
                        StoredReadiness(entity, period, Verdict.valueOf(it), r.get(PORTFOLIO_READINESS.BLOCKED_COUNT))
                    },
                )
            }
    }
}
