package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PriorEvidence.Companion.PRIOR_EVIDENCE
import com.dynokore.gst.evidence.application.migration.AcknowledgementHolder
import com.dynokore.gst.evidence.application.migration.PriorEvidenceRow
import com.dynokore.gst.evidence.application.migration.PriorEvidenceStore
import com.dynokore.gst.evidence.application.migration.StoredPriorEvidence
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `evidence.prior_evidence` over the caller's tenant-bound [DSLContext] (jOOQ only; RLS applies). Insert-only: the
 * runtime login holds select and insert, and this store offers no update or delete. Each insert first creates the
 * `prior_evidence` member of the quarter's retention unit (V21 function), as [ChainWiring.appendLedgers] does.
 * [lockUnits] does that for every unit of an import before anything is read, in the one lock order (V31);
 * only [lockAcknowledgementRefs] comes before it.
 */
class JooqPriorEvidenceStore(
    private val dsl: DSLContext,
) : PriorEvidenceStore {
    /**
     * One transaction-scoped advisory lock per distinct reference, in ascending order whatever order the caller gave.
     * The first key is the tenant alone, the second [ACK_LOCK_PREFIX] and the reference. Neither the identity lock
     * ([lock]) nor create_retention_partition can build this pair of texts: their first text is `<tenant>:<entity>`,
     * and their second begins `$LOCK_PREFIX` or `<chain table>:`, and `prior_evidence_ack` is no chain table. Equal
     * hashes of different texts would only serialise more than needed, or end in a deadlock that PostgreSQL detects.
     */
    override fun lockAcknowledgementRefs(
        tenant: TenantId,
        acknowledgementRefs: Collection<String>,
    ) {
        acknowledgementRefs.distinct().sorted().forEach {
            advisoryLock(tenant.value.toString(), "$ACK_LOCK_PREFIX$it")
        }
    }

    /** One statement over `prior_evidence_ack_idx`, for the tenant and every entity of it. */
    override fun acknowledgementHolders(
        tenant: TenantId,
        acknowledgementRefs: Collection<String>,
    ): List<AcknowledgementHolder> =
        dsl
            .select(
                PRIOR_EVIDENCE.ACKNOWLEDGEMENT_REF,
                PRIOR_EVIDENCE.ENTITY_REF,
                PRIOR_EVIDENCE.DOCUMENT_NUMBER,
                PRIOR_EVIDENCE.TRANSACTION_DATE,
            ).from(PRIOR_EVIDENCE)
            .where(
                PRIOR_EVIDENCE.TENANT_ID.eq(tenant.value),
                PRIOR_EVIDENCE.ACKNOWLEDGEMENT_REF.`in`(acknowledgementRefs),
            ).fetch {
                AcknowledgementHolder(
                    checkNotNull(it.value1()),
                    checkNotNull(it.value2()),
                    checkNotNull(it.value3()),
                    checkNotNull(it.value4()),
                )
            }

    /**
     * Two phases over the distinct periods in ascending order, whatever order the caller gave. Phase A locks every
     * unit row (`lock_import_unit`); only after the last one, phase B creates the missing members. Creating a member
     * locks the parent table until commit, so doing it while a later unit row is still to be locked would close a
     * cycle between two imports.
     */
    override fun lockUnits(
        tenant: TenantId,
        entityRef: String,
        periodRefs: Collection<String>,
    ) {
        val units = periodRefs.distinct().sorted()
        units.forEach { period ->
            dsl
                .select(
                    DSL.function(
                        DSL.name(SCHEMA, "lock_import_unit"),
                        SQLDataType.OTHER,
                        DSL.`val`(tenant.value),
                        DSL.`val`(entityRef),
                        DSL.`val`(period),
                        DSL.`val`(period + UNIT_END),
                    ),
                ).fetch()
        }
        units.forEach { createMember(tenant, entityRef, it) }
    }

    private fun createMember(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ) {
        dsl
            .select(
                DSL.function(
                    DSL.name(SCHEMA, "create_retention_partition"),
                    SQLDataType.VARCHAR,
                    DSL.inline("$SCHEMA.prior_evidence"),
                    DSL.`val`(tenant.value),
                    DSL.`val`(entityRef),
                    DSL.`val`(periodRef),
                    DSL.`val`(periodRef + UNIT_END),
                ),
            ).fetch()
    }

    override fun lock(
        tenant: TenantId,
        entityRef: String,
        documentNumber: String,
        transactionDate: LocalDate,
    ) {
        // See LOCK_PREFIX for why this never equals the key of create_retention_partition.
        advisoryLock("${tenant.value}:$entityRef", "$LOCK_PREFIX$documentNumber:$transactionDate")
    }

    /** Takes `pg_advisory_xact_lock(hashtext(first), hashtext(second))`, held until the transaction ends. */
    private fun advisoryLock(
        first: String,
        second: String,
    ) {
        dsl
            .select(
                DSL.function(
                    DSL.name("pg_catalog", "pg_advisory_xact_lock"),
                    SQLDataType.VARCHAR,
                    DSL.function(DSL.name("pg_catalog", "hashtext"), SQLDataType.INTEGER, DSL.`val`(first)),
                    DSL.function(DSL.name("pg_catalog", "hashtext"), SQLDataType.INTEGER, DSL.`val`(second)),
                ),
            ).fetch()
    }

    override fun find(
        tenant: TenantId,
        entityRef: String,
        documentNumber: String,
        transactionDate: LocalDate,
    ): StoredPriorEvidence? =
        dsl
            .select(PRIOR_EVIDENCE.ACKNOWLEDGEMENT_REF)
            .from(PRIOR_EVIDENCE)
            .where(
                PRIOR_EVIDENCE.TENANT_ID.eq(tenant.value),
                PRIOR_EVIDENCE.ENTITY_REF.eq(entityRef),
                PRIOR_EVIDENCE.DOCUMENT_NUMBER.eq(documentNumber),
                PRIOR_EVIDENCE.TRANSACTION_DATE.eq(transactionDate),
            ).limit(1)
            .fetchOne()
            ?.let { StoredPriorEvidence(checkNotNull(it.value1())) }

    override fun insert(
        tenant: TenantId,
        row: PriorEvidenceRow,
    ) {
        createMember(tenant, row.entityRef, row.periodRef)
        dsl
            .insertInto(PRIOR_EVIDENCE)
            .set(PRIOR_EVIDENCE.ID, row.id)
            .set(PRIOR_EVIDENCE.TENANT_ID, tenant.value)
            .set(PRIOR_EVIDENCE.ENTITY_REF, row.entityRef)
            .set(PRIOR_EVIDENCE.PERIOD_REF, row.periodRef)
            .set(PRIOR_EVIDENCE.DOCUMENT_NUMBER, row.documentNumber)
            .set(PRIOR_EVIDENCE.TRANSACTION_DATE, row.transactionDate)
            .set(PRIOR_EVIDENCE.ACKNOWLEDGEMENT_REF, row.acknowledgementRef)
            .set(PRIOR_EVIDENCE.PRIOR_SOLUTION, row.priorSolution)
            .set(PRIOR_EVIDENCE.PRIOR_DOC_UUID, row.priorDocUuid)
            .set(PRIOR_EVIDENCE.IMPORTED_AT, row.importedAt.atOffset(ZoneOffset.UTC))
            .set(PRIOR_EVIDENCE.IMPORTED_BY, row.importedBy)
            .execute()
    }

    private companion object {
        const val SCHEMA = "evidence"

        /** The exclusive end of a one-period unit: the period followed by this character (V21). */
        const val UNIT_END = "\u0001"

        /**
         * First part of the identity lock's second key. create_retention_partition locks on `<chain table>:<period>`,
         * here also `prior_evidence:<period>`; the keys differ because the identity has a further colon between the
         * document number and the date, and a period has none.
         */
        const val LOCK_PREFIX = "prior_evidence:"

        /** Starts the reference lock's second key; differs from [LOCK_PREFIX]. See [lockAcknowledgementRefs]. */
        const val ACK_LOCK_PREFIX = "prior_evidence_ack:"
    }
}
