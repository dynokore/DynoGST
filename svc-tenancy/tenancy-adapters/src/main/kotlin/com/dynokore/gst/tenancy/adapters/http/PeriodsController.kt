package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PackDeltaPreview
import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.application.period.SINGAPORE
import com.dynokore.gst.tenancy.application.rendition.ListRenditionAttestations
import com.dynokore.gst.tenancy.application.rendition.ListRenditionOutcome
import com.dynokore.gst.tenancy.application.rendition.RenditionPermissions
import com.dynokore.gst.tenancy.contract.api.PeriodsApi
import com.dynokore.gst.tenancy.contract.model.CalendarPackDeltaResponse
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionResponse
import com.dynokore.gst.tenancy.contract.model.PeriodRecordListResponse
import com.dynokore.gst.tenancy.contract.model.PeriodResponse
import com.dynokore.gst.tenancy.contract.model.RenditionAttestationRecord
import com.dynokore.gst.tenancy.contract.model.RenditionAttestationRecordList
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-08. The `periods` operations of the tenancy contract this service implements: confirmPeriods (INV-T-4929),
 * recordExtension and listPeriodRecords (INV-T-4930). The contract has no operation that edits a due date: a PATCH or
 * a PUT of a period is a route that does not exist and is refused by the framework (INV-REQ-81).
 */
@RestController
@Suppress("LongParameterList") // one parameter per collaborator of the period operations
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class PeriodsController(
    private val confirmPeriods: ConfirmPeriods,
    private val recordExtension: RecordExtension,
    private val listPeriodRecords: ListPeriodRecords,
    private val cursors: PeriodListCursor,
    private val caller: CallerSource,
    private val clock: ObjectProvider<Clock>,
    private val access: PeriodAccess,
    private val packDelta: PackDeltaPreview,
    private val packCursors: PackDeltaCursor,
    private val listRenditionAttestations: ListRenditionAttestations,
    private val renditionCursors: RenditionAttestationCursor,
) : PeriodsApi {
    override fun confirmPeriods(
        entityRef: String,
        idempotencyKey: UUID,
        confirmPeriodRequest: ConfirmPeriodRequest,
    ): ResponseEntity<PeriodResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(confirmationOf(entityRef, confirmPeriods.confirm(entityRef, confirmRequestOf(confirmPeriodRequest))))

    override fun recordExtension(
        entityRef: String,
        periodRef: String,
        idempotencyKey: UUID,
        periodExtensionRequest: PeriodExtensionRequest,
    ): ResponseEntity<PeriodExtensionResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                extensionOf(
                    recordExtension.record(entityRef, periodRef, extensionRequestOf(periodExtensionRequest)),
                ),
            )

    override fun listPeriodRecords(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<PeriodRecordListResponse> {
        if (limit < 1 || limit > MAX_LIMIT) invalid("limit is between 1 and $MAX_LIMIT")
        val now = clock.getIfAvailable(Clock::systemUTC)
        val user = caller.actor()
        val after = cursor?.let { cursors.open(it, entityRef, user, now.instant()) }
        val today = LocalDate.now(now.withZone(SINGAPORE))
        val outcome = listPeriodRecords.list(entityRef, limit, today, after)
        return ResponseEntity.ok(
            periodListOf(outcome) { last ->
                cursors.issue(entityRef, user, PagePosition(last.start, last.periodRef), now.instant())
            },
        )
    }

    override fun getCalendarPackDelta(
        entityRef: String,
        packIdentifier: String,
        version: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<CalendarPackDeltaResponse> {
        access.denial(entityRef, PeriodPermissions.PACK_DELTA_VIEW)?.let { forbidden(it) }
        if (limit < 1 || limit > MAX_LIMIT) invalid("limit is between 1 and $MAX_LIMIT")
        if (packIdentifier.isBlank()) invalid("packIdentifier is required")
        if (version.isBlank()) invalid("version is required")
        val now = clock.getIfAvailable(Clock::systemUTC).instant()
        val user = caller.actor()
        val scope = PackDeltaScope(entityRef, user, packIdentifier, version)
        val after = cursor?.let { packCursors.open(it, scope, now) }
        val page = packDelta.deltaPage(entityRef, packIdentifier, version, limit, after) ?: notFound()
        return ResponseEntity.ok(
            packDeltaOf(entityRef, packIdentifier, version, page) { last ->
                packCursors.issue(scope, last, now)
            },
        )
    }

    override fun listRenditionAttestations(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<RenditionAttestationRecordList> {
        // the key is decided before the cursor is opened, so a non-holder learns nothing about the cursor (the use
        // case decides it again before any read)
        access.denial(entityRef, RenditionPermissions.EVIDENCE_VIEW)?.let { forbidden(it) }
        val now = clock.getIfAvailable(Clock::systemUTC).instant()
        val user = caller.actor()
        val after = cursor?.let { renditionCursors.open(it, entityRef, user, now) }
        return when (val outcome = listRenditionAttestations.list(entityRef, limit, after)) {
            is ListRenditionOutcome.Forbidden -> forbidden(outcome.reason)
            is ListRenditionOutcome.Invalid -> invalid(outcome.reason)
            ListRenditionOutcome.NotFound -> notFound()
            is ListRenditionOutcome.Page ->
                ResponseEntity.ok(
                    RenditionAttestationRecordList(
                        items =
                            outcome.page.entries.map {
                                RenditionAttestationRecord(
                                    documentClass = it.documentClass,
                                    renderer = RenditionAttestationRecord.Renderer.valueOf(it.renderer.name),
                                    attestedBy = it.attestedBy,
                                    attestedAt = it.attestedAt.atOffset(ZoneOffset.UTC),
                                    sequence = it.seq,
                                )
                            },
                        nextCursor = outcome.page.last?.let { renditionCursors.issue(entityRef, user, it, now) },
                    ),
                )
        }
    }

    private companion object {
        /** The bound the contract publishes for `limit` (API-18). */
        const val MAX_LIMIT = 500
    }
}
