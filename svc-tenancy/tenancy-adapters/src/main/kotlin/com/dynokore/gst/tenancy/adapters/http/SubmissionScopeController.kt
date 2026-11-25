package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.GetSubmissionScope
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.SINGAPORE
import com.dynokore.gst.tenancy.application.period.SubmissionScopeOutcome
import com.dynokore.gst.tenancy.application.period.SubmissionScopePermissions
import com.dynokore.gst.tenancy.contract.api.SubmissionScopeApi
import com.dynokore.gst.tenancy.contract.model.SubmissionScopeResponse
import com.dynokore.gst.tenancy.contract.model.SubmissionScopeResponseCurrentPeriod
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

/**
 * TPL-08. getSubmissionScope (INV-REQ-1086), read by ingestion-svc for a backfill request. The caller must hold the
 * scoped permission key `transaction.ingest` on the entity, decided per request by [PeriodAccess], never by a role
 * name.
 * The clock is the Singapore calendar day, as `listPeriodRecords` uses it.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class SubmissionScopeController(
    private val scope: GetSubmissionScope,
    private val access: PeriodAccess,
    private val clock: ObjectProvider<Clock>,
    private val tx: TransactionRunner,
) : SubmissionScopeApi {
    override fun getSubmissionScope(entityRef: String): ResponseEntity<SubmissionScopeResponse> {
        access.denial(entityRef, SubmissionScopePermissions.READ)?.let { forbidden(it) }
        val today = LocalDate.now(clock.getIfAvailable(Clock::systemUTC).withZone(SINGAPORE))
        // one transaction of the bound tenant for both reads of the store (forced RLS)
        val outcome = tx.inTransaction { scope.get(entityRef, today) }
        val done = outcome as? SubmissionScopeOutcome.Done ?: notFound()
        return ResponseEntity.ok(
            SubmissionScopeResponse(
                entityRef = done.entityRef,
                submissionActivatedOn = done.activatedOn,
                currentPeriod = done.current?.let { SubmissionScopeResponseCurrentPeriod(it.start, it.end) },
            ),
        )
    }
}
