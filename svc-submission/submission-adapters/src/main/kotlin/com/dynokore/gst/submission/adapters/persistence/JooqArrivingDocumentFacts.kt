package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.arrival.ArrivingDocument
import com.dynokore.gst.submission.application.arrival.ArrivingDocumentFacts
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [ArrivingDocumentFacts] over `submission.submission` and `submission.obligation_register_entry` (INV-REQ-1110). It
 * opens no transaction and writes no row; every query names the tenant id besides the forced row level security.
 * A missing submission row, a null transaction date or a missing register entry gives null. A kind or state text that
 * is not a member name throws [IllegalStateException] (fail closed, never a default).
 */
class JooqArrivingDocumentFacts(
    private val dsl: DSLContext,
) : ArrivingDocumentFacts {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val s = Submission.SUBMISSION_

    override fun of(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): ArrivingDocument? {
        val row = submissionRow(tenant, docUuid, version)
        val transactionDate = row?.get(s.TRANSACTION_DATE)
        val entry = registerRow(tenant, docUuid)
        if (row == null || transactionDate == null || entry == null) return null
        val kind = checkNotNull(RowKind.entries.firstOrNull { it.name == entry.get(e.KIND) }) { "Unmapped kind" }
        val preceding = entry.get(e.PRECEDING_DOC_UUID)?.let { registerRow(tenant, it) }
        return ArrivingDocument(
            entityRef = checkNotNull(row.get(s.ENTITY_REF)),
            documentNumber = entry.get(e.DOCUMENT_NUMBER),
            transactionDate = transactionDate,
            creditNote = kind == RowKind.CREDIT_NOTE,
            precedingDocumentNumber = preceding?.get(e.DOCUMENT_NUMBER),
            precedingDocumentDate = preceding?.get(e.TRANSACTION_DATE),
            versions = states(tenant, docUuid),
        )
    }

    private fun submissionRow(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ) = dsl
        .select(s.ENTITY_REF, s.TRANSACTION_DATE)
        .from(s)
        .where(s.TENANT_ID.eq(tenant.value))
        .and(s.DOC_UUID.eq(docUuid))
        .and(s.VERSION.eq(version))
        .fetchOne()

    private fun registerRow(
        tenant: TenantId,
        docUuid: String,
    ) = dsl
        .select(e.DOCUMENT_NUMBER, e.KIND, e.PRECEDING_DOC_UUID, e.TRANSACTION_DATE)
        .from(e)
        .where(e.TENANT_ID.eq(tenant.value))
        .and(e.DOC_UUID.eq(docUuid))
        .fetchOne()

    private fun states(
        tenant: TenantId,
        docUuid: String,
    ): List<LifecycleState> =
        dsl
            .select(s.STATE)
            .from(s)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.DOC_UUID.eq(docUuid))
            .orderBy(s.VERSION.asc())
            .fetch(s.STATE)
            .map { state ->
                checkNotNull(LifecycleState.entries.firstOrNull { it.name == state }) { "Unmapped lifecycle state" }
            }
}
