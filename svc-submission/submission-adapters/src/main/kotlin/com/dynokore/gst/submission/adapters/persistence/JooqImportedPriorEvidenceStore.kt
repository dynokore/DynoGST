package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ImportedPriorEvidence
import com.dynokore.gst.submission.application.migration.ImportedPriorEvidenceStore
import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [ImportedPriorEvidenceStore] over `submission.imported_prior_evidence` in one transaction of the bound tenant:
 * `insert ... on conflict do nothing`; when no row was inserted the stored acknowledgement reference decides between
 * an identical repeat (false) and a [ContractViolation]. The table is insert-only. Values are never logged.
 */
class JooqImportedPriorEvidenceStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ImportedPriorEvidenceStore {
    private val e = ImportedPriorEvidence.IMPORTED_PRIOR_EVIDENCE

    override fun record(
        tenant: TenantId,
        record: ImportedPriorRecord,
        priorSolution: String,
    ): Boolean =
        runner.inTransaction {
            val inserted =
                dsl
                    .insertInto(e)
                    .set(e.TENANT_ID, tenant.value)
                    .set(e.ENTITY_REF, record.entityRef)
                    .set(e.DOCUMENT_NUMBER, record.documentNumber)
                    .set(e.TRANSACTION_DATE, record.transactionDate)
                    .set(e.ACKNOWLEDGEMENT_REF, record.acknowledgementRef)
                    .set(e.PRIOR_SOLUTION, priorSolution)
                    .onConflict(e.TENANT_ID, e.ENTITY_REF, e.DOCUMENT_NUMBER, e.TRANSACTION_DATE)
                    .doNothing()
                    .execute() == 1
            if (!inserted) {
                val stored =
                    dsl
                        .select(e.ACKNOWLEDGEMENT_REF)
                        .from(e)
                        .where(e.TENANT_ID.eq(tenant.value))
                        .and(e.ENTITY_REF.eq(record.entityRef))
                        .and(e.DOCUMENT_NUMBER.eq(record.documentNumber))
                        .and(e.TRANSACTION_DATE.eq(record.transactionDate))
                        .fetchOne(e.ACKNOWLEDGEMENT_REF)
                if (stored != record.acknowledgementRef) {
                    throw ContractViolation(
                        "PriorSubmissionEvidenceImported repeats a stored document with another acknowledgementRef",
                    )
                }
            }
            inserted
        }

    override fun listFor(
        tenant: TenantId,
        entityRef: String,
    ): List<ImportedPriorRecord> =
        runner.inTransaction {
            dsl
                .select(e.ENTITY_REF, e.DOCUMENT_NUMBER, e.TRANSACTION_DATE, e.ACKNOWLEDGEMENT_REF)
                .from(e)
                .where(e.TENANT_ID.eq(tenant.value))
                .and(e.ENTITY_REF.eq(entityRef))
                .orderBy(e.DOCUMENT_NUMBER, e.TRANSACTION_DATE)
                .fetch { r ->
                    ImportedPriorRecord(
                        checkNotNull(r.get(e.ENTITY_REF)),
                        checkNotNull(r.get(e.DOCUMENT_NUMBER)),
                        checkNotNull(r.get(e.TRANSACTION_DATE)),
                        checkNotNull(r.get(e.ACKNOWLEDGEMENT_REF)),
                    )
                }
        }
}
