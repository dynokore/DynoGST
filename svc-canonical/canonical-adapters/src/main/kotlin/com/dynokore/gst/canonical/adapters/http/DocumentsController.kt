package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.adapters.permission.EntityViewPermission
import com.dynokore.gst.canonical.application.exposure.ExposedVersion
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.gst.canonical.application.exposure.ReadExposureWindow
import com.dynokore.gst.canonical.contract.api.DocumentsApi
import com.dynokore.gst.canonical.contract.model.IngestedDocument
import com.dynokore.gst.canonical.contract.model.IngestedDocumentPage
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The document operations of the canonical contract (TPL-08). An operation that is not overridden here answers 501
 * until the task named for it in `operations.tsv` overrides its method: the generated interface's default is
 * NOT_IMPLEMENTED.
 */
@RestController
open class DocumentsController(
    private val exposure: ReadExposureWindow,
    private val permission: EntityViewPermission,
    private val tenants: TenantContext,
    private val cursors: IngestedDocumentCursors,
) : DocumentsApi {
    /**
     * INV-REQ-867: the versions a principal submitted in a recording window. The entities of the window are decided
     * first and only the permitted ones reach the page read, so the filter applies before paging. A caller permitted
     * for none gets the same empty answer as for an empty window (OWASP API1). An unavailable decision refuses the
     * whole request, and a failed read propagates: neither gives a partial list.
     */
    override fun listIngestedDocuments(
        submittedBy: String,
        recordedFrom: OffsetDateTime,
        recordedTo: OffsetDateTime,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<IngestedDocumentPage> {
        val actor = tenants.currentActor() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val window = windowOf(submittedBy, recordedFrom, recordedTo)
        if (limit < 1) invalid("limit must be at least 1")
        val binding = CursorBinding(tenants.current().value.toString(), actor, window)
        val after = cursor?.let { cursors.open(it, binding) ?: invalid("cursor is not valid") }
        val permitted =
            permission.permittedAmong(actor, exposure.entityRefs(window))
                ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        // one row more than the page tells whether another page follows
        val rows = exposure.page(window, permitted, limit + 1, after)
        val shown = rows.take(limit)
        val next = if (rows.size > limit) cursors.issue(binding, shown.last().position) else null
        return ResponseEntity.ok(IngestedDocumentPage(shown.map(::item), next))
    }

    private fun windowOf(
        submittedBy: String,
        recordedFrom: OffsetDateTime,
        recordedTo: OffsetDateTime,
    ): ExposureWindow {
        // a date-time of the contract has a four-digit year (RFC 3339)
        if (recordedFrom.year !in YEARS || recordedTo.year !in YEARS) {
            invalid("recordedFrom and recordedTo must be RFC 3339 date-times")
        }
        val from = recordedFrom.toInstant()
        val to = recordedTo.toInstant()
        if (!from.isBefore(to)) invalid("recordedFrom must be before recordedTo")
        return ExposureWindow(submittedBy, from, to)
    }

    private fun item(version: ExposedVersion): IngestedDocument =
        IngestedDocument(
            sourceSystem = version.sourceSystem,
            sourceKey = version.sourceKey,
            sourceRevision = Math.toIntExact(version.sourceRevision),
            docUuid = version.docUuid,
            version = version.version,
            entityRef = version.entityRef,
            recordedAt = version.recordedAt.atOffset(ZoneOffset.UTC),
        )

    private fun invalid(detail: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, detail)

    private companion object {
        val YEARS = 1..9999
    }
}
