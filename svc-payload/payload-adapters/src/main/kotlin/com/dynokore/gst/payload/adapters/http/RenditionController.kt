package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleCheck
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleCheckOutcome
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleMetadata
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleMetadataOutcome
import com.dynokore.gst.payload.application.rendition.StoredSampleCheck
import com.dynokore.gst.payload.application.rendition.StoredSampleMetadata
import com.dynokore.gst.payload.contract.api.RenditionApi
import com.dynokore.gst.payload.contract.model.RenditionSampleCheckMetadata
import com.dynokore.gst.payload.contract.model.RenditionSampleCheckMetadataRead
import com.dynokore.gst.payload.contract.model.RenditionSampleCheckRead
import com.dynokore.gst.payload.contract.model.RenditionSampleCheckRecord
import com.dynokore.gst.payload.contract.model.RenditionStanding
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * The rendition operations of the payload contract (TPL-08). The two reads of a stored sample check are served here
 * (INV-REQ-1033, INV-REQ-1036): `getRenditionSampleCheck` with the stored bytes, `getRenditionSampleCheckMetadata`
 * without any bytes member. Both are decided by the scoped key `rendition-evidence.view` at entity scope inside the
 * use case, never by a role name; the acting user is the `sub` of the verified end-user principal. Every other
 * operation of the tag answers 501 until the task named for it in `operations.tsv` overrides its method: the
 * generated interface's default is NOT_IMPLEMENTED.
 */
@RestController
open class RenditionController(
    private val readCheck: ReadRenditionSampleCheck,
    private val readMetadata: ReadRenditionSampleMetadata,
    private val transactions: TransactionRunner,
    private val http: ObjectProvider<HttpServletRequest>,
) : RenditionApi {
    override fun getRenditionSampleCheck(
        entityRef: String,
        checkId: String,
    ): ResponseEntity<RenditionSampleCheckRead> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        return when (
            val outcome =
                transactions.inTransaction {
                    readCheck.execute(
                        principal.actor,
                        entityRef,
                        checkId,
                    )
                }
        ) {
            is ReadRenditionSampleCheckOutcome.Found -> {
                ResponseEntity.ok(RenditionSampleCheckRead(recordOf(outcome.check), RenditionStanding(outcome.open)))
            }

            ReadRenditionSampleCheckOutcome.Forbidden -> {
                OverrideHttp.problem(ClosedErrorCode.ACCESS_DENIED)
            }

            ReadRenditionSampleCheckOutcome.NotFound -> {
                OverrideHttp.problem(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    override fun getRenditionSampleCheckMetadata(
        entityRef: String,
        checkId: String,
    ): ResponseEntity<RenditionSampleCheckMetadataRead> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        return when (
            val outcome = transactions.inTransaction { readMetadata.execute(principal.actor, entityRef, checkId) }
        ) {
            is ReadRenditionSampleMetadataOutcome.Found -> {
                ResponseEntity.ok(
                    RenditionSampleCheckMetadataRead(metadataOf(outcome.metadata), RenditionStanding(outcome.open)),
                )
            }

            ReadRenditionSampleMetadataOutcome.Forbidden -> {
                OverrideHttp.problem(ClosedErrorCode.ACCESS_DENIED)
            }

            ReadRenditionSampleMetadataOutcome.NotFound -> {
                OverrideHttp.problem(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    private fun principalOrNull(): EndUserPrincipal? =
        http.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal

    private fun recordOf(check: StoredSampleCheck): RenditionSampleCheckRecord =
        RenditionSampleCheckRecord(
            check.checkId,
            check.entityRef,
            check.scenario,
            RenditionSampleCheckRecord.SubmissionType.valueOf(check.submissionType.name),
            Base64.getEncoder().encodeToString(check.sampleContent),
            check.sampleSha256,
            RenditionSampleCheckRecord.Result.valueOf(check.result.name),
            check.textFound,
            check.missingWording,
            OverrideHttp.utc(check.checkedAt),
            check.checkedBy,
            Base64.getEncoder().encodeToString(check.artefactBytes),
            RenditionSampleCheckRecord.FindingCode.PAYMinus014.takeIf { check.result == SampleResult.FAILED },
        )

    private fun metadataOf(check: StoredSampleMetadata): RenditionSampleCheckMetadata =
        RenditionSampleCheckMetadata(
            check.checkId,
            check.entityRef,
            check.scenario,
            RenditionSampleCheckMetadata.SubmissionType.valueOf(check.submissionType.name),
            check.sampleSha256,
            RenditionSampleCheckMetadata.Result.valueOf(check.result.name),
            check.textFound,
            check.missingWording,
            OverrideHttp.utc(check.checkedAt),
            check.checkedBy,
            RenditionSampleCheckMetadata.FindingCode.PAYMinus014.takeIf { check.result == SampleResult.FAILED },
        )
}
