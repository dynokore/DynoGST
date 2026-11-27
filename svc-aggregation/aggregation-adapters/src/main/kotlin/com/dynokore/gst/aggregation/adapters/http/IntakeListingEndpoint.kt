package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.adapters.permission.EntityViewPermission
import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.ListSubmittedIntake
import com.dynokore.gst.aggregation.application.ListedIntake
import com.dynokore.gst.aggregation.contract.model.IntakeReceipt
import com.dynokore.gst.aggregation.contract.model.IntakeReceiptPage
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `listAggregationIntake` (INV-REQ-867; INV-AC-28766), served by [TransactionIntakeController], the one bean of the
 * generated `IntakeApi`. The entities of the window are decided first and only the permitted ones reach the page read,
 * so the filter applies before paging. A caller permitted for none gets the answer of an empty window (OWASP API1). An
 * unavailable decision refuses the whole request with 403 SRT-004, and a failed read propagates: neither gives a
 * partial list. A window that is not `recordedFrom` before `recordedTo`, a `limit` outside 1 to 500 and a cursor that
 * is not valid for this principal and these filters answer 400.
 */
class IntakeListingEndpoint(
    private val listing: ListSubmittedIntake,
    private val permission: EntityViewPermission,
    private val cursors: IntakeListingCursors,
    private val tenants: TenantContext,
) {
    fun list(
        submittedBy: String,
        recordedFrom: OffsetDateTime,
        recordedTo: OffsetDateTime,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<IntakeReceiptPage> {
        val actor =
            tenants.currentActor()?.takeIf { it.isNotBlank() }
                ?: return refused(ClosedErrorCode.ACCESS_DENIED, null)
        val broken = broken(recordedFrom, recordedTo, limit)
        return if (broken != null) {
            refused(ClosedErrorCode.CONTRACT_VIOLATION, broken)
        } else {
            val filter = IntakeListingFilter(submittedBy, recordedFrom.toInstant(), recordedTo.toInstant())
            try {
                page(IntakeListingCursors.Scope(tenants.current().toString(), actor, filter), limit, cursor)
            } catch (_: SecretNotFound) {
                // fail closed: with the cursor key unreadable no page is issued, so none can carry a nextCursor
                refused(ClosedErrorCode.INTERNAL_ERROR, "the cursor key is not available")
            }
        }
    }

    /**
     * A constraint of the contract on a parameter of `listAggregationIntake` (`submittedBy` too long, `limit` above
     * 500), broken before the controller runs: 400 SRT-001 with a fixed detail, nothing of the request echoed
     * (API-22). A violation of anything else, or none at all, is thrown on unchanged.
     */
    fun constraintBroken(violation: ConstraintViolationException): ResponseEntity<IntakeReceiptPage> {
        val violations = violation.constraintViolations.orEmpty()
        val ofTheListing =
            violations.isNotEmpty() &&
                violations.all { it.propertyPath.first().name == OPERATION }
        if (!ofTheListing) throw violation
        return refused(ClosedErrorCode.CONTRACT_VIOLATION, "the request does not match the contract")
    }

    /** What of the request breaks the contract, or null. A date-time has a four-digit year (RFC 3339). */
    private fun broken(
        recordedFrom: OffsetDateTime,
        recordedTo: OffsetDateTime,
        limit: Int,
    ): String? =
        when {
            recordedFrom.year !in YEARS || recordedTo.year !in YEARS -> {
                "recordedFrom and recordedTo must be RFC 3339 date-times"
            }

            !recordedFrom.toInstant().isBefore(recordedTo.toInstant()) -> {
                "recordedFrom must be before recordedTo"
            }

            limit !in 1..MAX_LIMIT -> {
                "limit is outside 1 to $MAX_LIMIT"
            }

            else -> {
                null
            }
        }

    private fun page(
        scope: IntakeListingCursors.Scope,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<IntakeReceiptPage> {
        val after = cursor?.let { cursors.decode(it, scope) }
        return if (cursor != null && after == null) {
            refused(ClosedErrorCode.CONTRACT_VIOLATION, "the cursor is not valid here")
        } else {
            permitted(scope, after, limit)
        }
    }

    /** Every request decides again, a request with a cursor too: a cursor carries no permission. */
    private fun permitted(
        scope: IntakeListingCursors.Scope,
        after: IntakeListingPosition?,
        limit: Int,
    ): ResponseEntity<IntakeReceiptPage> {
        val permitted =
            permission.permittedAmong(scope.actor, listing.entityRefs(scope.filter))
                ?: return refused(ClosedErrorCode.ACCESS_DENIED, null)
        val page = listing.page(scope.filter, permitted, after, limit)
        return ResponseEntity.ok(
            IntakeReceiptPage(page.items.map(::item), page.next?.let { cursors.encode(it, scope) }),
        )
    }

    private fun item(intake: ListedIntake) =
        IntakeReceipt(
            intakeOperation = IntakeReceipt.IntakeOperation.valueOf(intake.intakeOperation.name),
            entityRef = intake.entityRef,
            sourceKey = intake.sourceKey,
            sourceRevision = Math.toIntExact(intake.sourceRevision),
            aggregateType = IntakeReceipt.AggregateType.valueOf(intake.aggregateType.name),
            recordedAt = intake.recordedAt.atOffset(ZoneOffset.UTC),
            aggregateIds = intake.aggregateIds.map { it.toString() },
        )

    /** The API-20 envelope of [code], written as the body of the operation's generic response. */
    @Suppress("UNCHECKED_CAST")
    private fun refused(
        code: ClosedErrorCode,
        detail: String?,
    ): ResponseEntity<IntakeReceiptPage> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val rendered = Problems.render(code, correlation, detail)
        return ResponseEntity
            .status(HttpStatus.valueOf(rendered.status))
            .headers { headers -> rendered.headers.forEach { (name, value) -> headers.set(name, value) } }
            .body(rendered.body) as ResponseEntity<IntakeReceiptPage>
    }

    private companion object {
        const val MAX_LIMIT = 500
        const val OPERATION = "listAggregationIntake"
        val YEARS = 1..9999
    }
}
