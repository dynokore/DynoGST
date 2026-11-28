package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionPackage.Companion.SUBMISSION_PACKAGE
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.records.SubmissionPackageRecord
import com.dynokore.gst.transport.application.PackageLog
import com.dynokore.gst.transport.application.PackageRecord
import com.dynokore.gst.transport.application.PackageSend
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.OutcomeKind
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The package log over jOOQ (INV-REQ-538, INV-REQ-424). [begin] writes the package row, one attempt row per member
 * (carrying the packageRef) and one egress row per attempt in one transaction; the primary key (tenant, packageRef)
 * decides a race and the loser writes nothing. [complete] sets the outcome on the package and its attempts only where
 * none is set. Rows are tenant data under forced row level security.
 */
class JooqPackageLog(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
) : PackageLog {
    override fun find(packageRef: String): PackageRecord? = runner.inTransaction { stored(packageRef) }

    override fun begin(
        send: PackageSend,
        at: Instant,
    ): PackageRecord? {
        val startedAt = at.atOffset(ZoneOffset.UTC)
        return runner.inTransaction {
            val tenant = tenants.current().value
            val inserted =
                dsl
                    .insertInto(SUBMISSION_PACKAGE)
                    .set(SUBMISSION_PACKAGE.TENANT_ID, tenant)
                    .set(SUBMISSION_PACKAGE.PACKAGE_REF, send.packageRef)
                    .set(SUBMISSION_PACKAGE.ENTITY_REF, send.entity.value)
                    .set(SUBMISSION_PACKAGE.FINGERPRINT, send.fingerprint)
                    .set(SUBMISSION_PACKAGE.ADAPTER_ID, send.adapter.value)
                    .set(SUBMISSION_PACKAGE.STARTED_AT, startedAt)
                    .onConflictDoNothing()
                    .execute()
            if (inserted == 0) {
                stored(send.packageRef)
            } else {
                send.members.forEach { insertMember(tenant, send, it, startedAt) }
                null
            }
        }
    }

    private fun insertMember(
        tenant: UUID,
        send: PackageSend,
        doc: DocRef,
        startedAt: OffsetDateTime,
    ) {
        val attempt = UUID.randomUUID()
        dsl
            .insertInto(SUBMISSION_ATTEMPT)
            .set(SUBMISSION_ATTEMPT.ID, attempt)
            .set(SUBMISSION_ATTEMPT.TENANT_ID, tenant)
            .set(SUBMISSION_ATTEMPT.ENTITY_REF, send.entity.value)
            .set(SUBMISSION_ATTEMPT.DOC_UUID, doc.docUuid)
            .set(SUBMISSION_ATTEMPT.VERSION, doc.version)
            .set(SUBMISSION_ATTEMPT.ADAPTER_ID, send.adapter.value)
            .set(SUBMISSION_ATTEMPT.STARTED_AT, startedAt)
            .set(SUBMISSION_ATTEMPT.PACKAGE_REF, send.packageRef)
            .execute()
        dsl
            .insertInto(ATTEMPT_EGRESS)
            .set(ATTEMPT_EGRESS.ID, UUID.randomUUID())
            .set(ATTEMPT_EGRESS.TENANT_ID, tenant)
            .set(ATTEMPT_EGRESS.ATTEMPT_ID, attempt)
            .set(ATTEMPT_EGRESS.ADAPTER_ID, send.adapter.value)
            .set(ATTEMPT_EGRESS.SENT_AT, startedAt)
            .execute()
    }

    override fun complete(
        packageRef: String,
        outcome: TransportOutcome,
        at: Instant,
    ): TransportOutcome {
        val finishedAt = at.atOffset(ZoneOffset.UTC)
        val acknowledgement = (outcome as? TransportOutcome.Accepted)?.acknowledgementId
        val reasons = (outcome as? TransportOutcome.Rejected)?.let { ReasonsJson.write(it.reasons) }
        return runner.inTransaction {
            val updated =
                dsl
                    .update(SUBMISSION_PACKAGE)
                    .set(SUBMISSION_PACKAGE.OUTCOME, outcome.kind.name)
                    .set(SUBMISSION_PACKAGE.FINISHED_AT, finishedAt)
                    .set(SUBMISSION_PACKAGE.ACKNOWLEDGEMENT_ID, acknowledgement)
                    .set(SUBMISSION_PACKAGE.REASONS, reasons)
                    .where(SUBMISSION_PACKAGE.PACKAGE_REF.eq(packageRef), SUBMISSION_PACKAGE.OUTCOME.isNull)
                    .execute()
            if (updated > 0) {
                dsl
                    .update(SUBMISSION_ATTEMPT)
                    .set(SUBMISSION_ATTEMPT.OUTCOME, outcome.kind.name)
                    .set(SUBMISSION_ATTEMPT.FINISHED_AT, finishedAt)
                    .set(SUBMISSION_ATTEMPT.ACKNOWLEDGEMENT_ID, acknowledgement)
                    .set(SUBMISSION_ATTEMPT.REASONS, reasons)
                    .where(SUBMISSION_ATTEMPT.PACKAGE_REF.eq(packageRef), SUBMISSION_ATTEMPT.OUTCOME.isNull)
                    .execute()
            }
            checkNotNull(stored(packageRef)?.outcome) { "package $packageRef has no stored outcome" }
        }
    }

    private fun stored(packageRef: String): PackageRecord? =
        dsl
            .selectFrom(SUBMISSION_PACKAGE)
            .where(SUBMISSION_PACKAGE.PACKAGE_REF.eq(packageRef))
            .fetchOne()
            ?.let(::record)

    private fun record(row: SubmissionPackageRecord): PackageRecord =
        PackageRecord(
            fingerprint = checkNotNull(row.fingerprint),
            startedAt = checkNotNull(row.startedAt).toInstant(),
            outcome =
                when (row.outcome) {
                    OutcomeKind.ACCEPTED.name -> TransportOutcome.Accepted(checkNotNull(row.acknowledgementId))
                    OutcomeKind.REJECTED.name -> TransportOutcome.Rejected(ReasonsJson.read(checkNotNull(row.reasons)))
                    OutcomeKind.TRANSIENT_FAILURE.name -> TransportOutcome.TransientFailure
                    else -> null
                },
        )
}
