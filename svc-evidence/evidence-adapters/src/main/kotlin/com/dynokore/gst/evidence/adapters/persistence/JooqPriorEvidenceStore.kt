package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PriorEvidence.Companion.PRIOR_EVIDENCE
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
 */
class JooqPriorEvidenceStore(
    private val dsl: DSLContext,
) : PriorEvidenceStore {
    override fun lock(
        tenant: TenantId,
        entityRef: String,
        documentNumber: String,
        transactionDate: LocalDate,
    ) {
        // Transaction-scoped; the second key has its own prefix, so it never equals the key of
        // create_retention_partition.
        dsl
            .select(
                DSL.function(
                    DSL.name("pg_catalog", "pg_advisory_xact_lock"),
                    SQLDataType.VARCHAR,
                    DSL.function(
                        DSL.name("pg_catalog", "hashtext"),
                        SQLDataType.INTEGER,
                        DSL.`val`("${tenant.value}:$entityRef"),
                    ),
                    DSL.function(
                        DSL.name("pg_catalog", "hashtext"),
                        SQLDataType.INTEGER,
                        DSL.`val`("$LOCK_PREFIX$documentNumber:$transactionDate"),
                    ),
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
        dsl
            .select(
                DSL.function(
                    DSL.name(SCHEMA, "create_retention_partition"),
                    SQLDataType.VARCHAR,
                    DSL.inline("$SCHEMA.prior_evidence"),
                    DSL.`val`(tenant.value),
                    DSL.`val`(row.entityRef),
                    DSL.`val`(row.periodRef),
                    DSL.`val`(row.periodRef + UNIT_END),
                ),
            ).fetch()
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

        /** Keeps the identity lock apart from the `<chain table>:<period>` key of create_retention_partition. */
        const val LOCK_PREFIX = "prior_evidence:"
    }
}
