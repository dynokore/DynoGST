package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.RATE_SOURCE_ACCEPTANCE
import com.dynokore.gst.rules.application.RateSourceAcceptance
import com.dynokore.gst.rules.application.RateSourceAcceptanceStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The rate source acceptances over `rules.rate_source_acceptance` (INV-T-24951, INV-REQ-504), jOOQ only, in the
 * caller's open transaction. Row-level security hides every other tenant's rows; the tenant is also named in every
 * statement.
 */
class JooqRateSourceAcceptanceStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : RateSourceAcceptanceStore {
    override fun replace(acceptance: RateSourceAcceptance) {
        // the precision of timestamptz, so the answer and a later read state the same instant
        val acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(RATE_SOURCE_ACCEPTANCE)
            .set(RATE_SOURCE_ACCEPTANCE.TENANT_ID, acceptance.tenant.value)
            .set(RATE_SOURCE_ACCEPTANCE.SOURCE_ID, acceptance.sourceId)
            .set(RATE_SOURCE_ACCEPTANCE.RATIONALE, acceptance.rationale)
            .set(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT, acceptedAt)
            .onConflict(RATE_SOURCE_ACCEPTANCE.TENANT_ID, RATE_SOURCE_ACCEPTANCE.SOURCE_ID)
            .doUpdate()
            .set(RATE_SOURCE_ACCEPTANCE.RATIONALE, acceptance.rationale)
            .set(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT, acceptedAt)
            .execute()
    }

    override fun byTenant(tenant: TenantId): List<RateSourceAcceptance> =
        dsl
            .selectFrom(RATE_SOURCE_ACCEPTANCE)
            .where(RATE_SOURCE_ACCEPTANCE.TENANT_ID.eq(tenant.value))
            .orderBy(RATE_SOURCE_ACCEPTANCE.SOURCE_ID)
            .fetch()
            .map {
                RateSourceAcceptance(
                    tenant = tenant,
                    sourceId = checkNotNull(it.get(RATE_SOURCE_ACCEPTANCE.SOURCE_ID)),
                    rationale = checkNotNull(it.get(RATE_SOURCE_ACCEPTANCE.RATIONALE)),
                    expiresOn = checkNotNull(it.get(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON)),
                    acceptedBy = checkNotNull(it.get(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY)),
                )
            }

    /** When the stored acceptance of [sourceId] was recorded; the port's value carries no time. */
    fun acceptedAt(
        tenant: TenantId,
        sourceId: String,
    ): OffsetDateTime =
        checkNotNull(
            dsl
                .select(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT)
                .from(RATE_SOURCE_ACCEPTANCE)
                .where(RATE_SOURCE_ACCEPTANCE.TENANT_ID.eq(tenant.value))
                .and(RATE_SOURCE_ACCEPTANCE.SOURCE_ID.eq(sourceId))
                .fetchOne(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT),
        ) { "no stored acceptance of the source" }
}
