package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.RATE_SOURCE_ACCEPTANCE
import com.dynokore.gst.rules.application.RateSourceAcceptance
import com.dynokore.gst.rules.application.RateSourceAcceptanceStore
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The rate source acceptances over `rules.rate_source_acceptance` (INV-T-24951, INV-REQ-504), jOOQ only, in the
 * caller's open transaction. Row-level security hides every other tenant's rows; the tenant is also named in every
 * statement.
 *
 * An acceptance overrides a blocking validation, so every write records one audit entry in the same transaction, with
 * the values of the row it replaces as the prior state (INV-REQ-861, INV-REQ-1226). A failure of [audit] propagates, so
 * the write does not commit.
 */
class JooqRateSourceAcceptanceStore(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val audit: AuditWriter,
) : RateSourceAcceptanceStore {
    private val mapper = JsonMapper()

    override fun replace(acceptance: RateSourceAcceptance) {
        // the precision of timestamptz, so the answer and a later read state the same instant
        val acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
        val priorState = write(acceptance, acceptedAt)
        audit.record(
            AuditEntry(
                actor = acceptance.acceptedBy,
                action = AUDIT_ACTION,
                scope = acceptance.sourceId,
                reason = acceptance.rationale,
                priorStateJson = priorState,
            ),
            auditContext(acceptance),
        )
    }

    /**
     * Writes the row and answers the prior state. The insert waits for an uncommitted row of the same tenant and
     * source; when it inserts nothing, the row is locked before it is read, so the prior state is that of the replaced
     * row also when two calls run at once.
     */
    private fun write(
        acceptance: RateSourceAcceptance,
        acceptedAt: OffsetDateTime,
    ): String {
        val inserted =
            dsl
                .insertInto(RATE_SOURCE_ACCEPTANCE)
                .set(RATE_SOURCE_ACCEPTANCE.TENANT_ID, acceptance.tenant.value)
                .set(RATE_SOURCE_ACCEPTANCE.SOURCE_ID, acceptance.sourceId)
                .set(RATE_SOURCE_ACCEPTANCE.RATIONALE, acceptance.rationale)
                .set(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
                .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
                .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT, acceptedAt)
                .onConflict(RATE_SOURCE_ACCEPTANCE.TENANT_ID, RATE_SOURCE_ACCEPTANCE.SOURCE_ID)
                .doNothing()
                .execute()
        return if (inserted == 1) NO_PRIOR_STATE else replaceLocked(acceptance, acceptedAt)
    }

    private fun replaceLocked(
        acceptance: RateSourceAcceptance,
        acceptedAt: OffsetDateTime,
    ): String {
        val row =
            RATE_SOURCE_ACCEPTANCE.TENANT_ID
                .eq(acceptance.tenant.value)
                .and(RATE_SOURCE_ACCEPTANCE.SOURCE_ID.eq(acceptance.sourceId))
        val replaced =
            checkNotNull(
                dsl
                    .selectFrom(RATE_SOURCE_ACCEPTANCE)
                    .where(row)
                    .forUpdate()
                    .fetchOne(),
            ) {
                "no stored acceptance of the source"
            }
        val priorState =
            mapper.writeValueAsString(
                linkedMapOf(
                    "rationale" to checkNotNull(replaced.get(RATE_SOURCE_ACCEPTANCE.RATIONALE)),
                    "expiresOn" to checkNotNull(replaced.get(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON)).toString(),
                    "acceptedBy" to checkNotNull(replaced.get(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY)),
                    "acceptedAt" to
                        checkNotNull(replaced.get(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT)).toInstant().toString(),
                ),
            )
        dsl
            .update(RATE_SOURCE_ACCEPTANCE)
            .set(RATE_SOURCE_ACCEPTANCE.RATIONALE, acceptance.rationale)
            .set(RATE_SOURCE_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
            .set(RATE_SOURCE_ACCEPTANCE.ACCEPTED_AT, acceptedAt)
            .where(row)
            .execute()
        return priorState
    }

    private fun auditContext(acceptance: RateSourceAcceptance): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = acceptance.tenant,
            entity = EntityRef.TENANT_WIDE,
            docUuid = null,
            transactionDate = null,
            aggregateKey = acceptance.sourceId,
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
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

    private companion object {
        const val AUDIT_ACTION = "acceptRateSource"
        const val NO_PRIOR_STATE = "{}"
    }
}
