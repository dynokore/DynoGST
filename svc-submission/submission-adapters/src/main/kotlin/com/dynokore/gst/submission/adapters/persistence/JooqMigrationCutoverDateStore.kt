package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.MigrationCutoverDate
import com.dynokore.gst.submission.application.startdate.MigrationCutoverDates
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * [MigrationCutoverDates] over `submission.migration_cutover_date` in one transaction of the bound tenant: one
 * `insert ... on conflict do update ... where stored effective date < new effective date` statement, so an equal or
 * older effective date changes nothing and concurrent writers cannot race the result. Forced row level security
 * applies. Values are never logged.
 */
class JooqMigrationCutoverDateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : MigrationCutoverDates {
    private val o = MigrationCutoverDate.MIGRATION_CUTOVER_DATE

    override fun record(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
        effectiveDate: LocalDate,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(o)
                .set(o.TENANT_ID, tenant.value)
                .set(o.ENTITY_REF, entityRef)
                .set(o.CUTOVER_DATE, date)
                .set(o.EFFECTIVE_DATE, effectiveDate)
                .onConflict(o.TENANT_ID, o.ENTITY_REF)
                .doUpdate()
                .set(o.CUTOVER_DATE, DSL.excluded(o.CUTOVER_DATE))
                .set(o.EFFECTIVE_DATE, DSL.excluded(o.EFFECTIVE_DATE))
                .where(o.EFFECTIVE_DATE.lt(DSL.excluded(o.EFFECTIVE_DATE)))
                .execute() == 1
        }

    override fun find(
        tenant: TenantId,
        entityRef: String,
    ): LocalDate? =
        runner.inTransaction {
            dsl
                .select(o.CUTOVER_DATE)
                .from(o)
                .where(o.TENANT_ID.eq(tenant.value))
                .and(o.ENTITY_REF.eq(entityRef))
                .fetchOne(o.CUTOVER_DATE)
        }
}
