package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.flowcontext.FlowEntityKind
import com.dynokore.gst.access.application.flowcontext.FlowPrincipalKind
import com.dynokore.gst.access.application.flowcontext.IssueFlowContextResult
import com.dynokore.gst.access.application.flowcontext.IssueFlowContextUseCase
import com.dynokore.gst.access.contract.model.IssueFlowContextResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

/**
 * `issueFlowContext` (INV-REQ-1202, owner answer 2026-11-18, option A). The caller service is read only from the
 * verified [ServiceCaller], never from the body, a header or a parameter; the use case decides, and this class maps the
 * result: issued 200, caller not served 403 SRT-004, invalid request 400 SRT-001, signing unavailable 503 SRT-013 (fail
 * closed, nothing issued). Nothing is stored. One structured log line per call carries the fields `callerService`,
 * `flowId`, `tenant`, `entityKind`, `entityRef`, `principalKind` and `result`; the compact form and the signature are
 * never logged (NIST SP 800-53 AU-2, SI-4).
 */
class IssueFlowContextOperation(
    private val issuer: IssueFlowContextUseCase,
    private val serviceCaller: ServiceCaller,
    private val tenants: TenantContext,
) {
    @Suppress("LongParameterList")
    fun issue(
        tenant: String,
        entityKind: FlowEntityKind,
        entityRef: String?,
        principalKind: FlowPrincipalKind,
        flowId: String,
    ): ResponseEntity<IssueFlowContextResponse> {
        val caller = serviceCaller.current()
        val tenantId = runCatching { TenantId.of(tenant) }.getOrNull()
        val bound = runCatching { tenants.current() }.getOrNull()
        val mismatch = tenantId != null && caller != null && bound != tenantId
        val result =
            when {
                tenantId == null -> {
                    // a caller that is not served is refused before the request is judged, as in the use case
                    if (caller == null) IssueFlowContextResult.CallerNotServed else IssueFlowContextResult.InvalidRequest
                }

                // the request tenant must equal the tenant of the propagated token; a token without a tenant (a platform
                // operator, or nothing bound) never equals it (ruling INV-T-24991, OWASP ASVS V4.1 deny by default)
                mismatch -> IssueFlowContextResult.CallerNotServed

                else -> issuer.issue(caller, tenantId, entityKind, entityRef, principalKind, flowId)
            }
        log(caller, flowId, tenant, entityKind, entityRef, principalKind, if (mismatch) TENANT_MISMATCH else outcomeOf(result))
        if (result is IssueFlowContextResult.Issued) {
            return ResponseEntity.ok(IssueFlowContextResponse(result.compact, result.expiresAt))
        }
        throw refusalOf(result)
    }

    /** The refusal of a result that issued nothing: caller 403 SRT-004, request 400 SRT-001, signing 503 SRT-013. */
    private fun refusalOf(result: IssueFlowContextResult): ApiRefusal =
        when (result) {
            is IssueFlowContextResult.Issued -> {
                error("an issued flow context is no refusal")
            }

            IssueFlowContextResult.CallerNotServed -> {
                ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            IssueFlowContextResult.InvalidRequest -> {
                ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "the request does not describe a flow")
            }

            IssueFlowContextResult.SigningUnavailable -> {
                ApiRefusal(ClosedErrorCode.SERVICE_NOT_READY)
            }
        }

    private fun outcomeOf(result: IssueFlowContextResult): String =
        when (result) {
            is IssueFlowContextResult.Issued -> ISSUED
            IssueFlowContextResult.CallerNotServed -> CALLER_NOT_SERVED
            IssueFlowContextResult.InvalidRequest -> INVALID_REQUEST
            IssueFlowContextResult.SigningUnavailable -> SIGNING_UNAVAILABLE
        }

    @Suppress("LongParameterList")
    private fun log(
        caller: String?,
        flowId: String,
        tenant: String,
        entityKind: FlowEntityKind,
        entityRef: String?,
        principalKind: FlowPrincipalKind,
        result: String,
    ) {
        LOG
            .atInfo()
            .addKeyValue("callerService", caller)
            .addKeyValue("flowId", flowId)
            .addKeyValue("tenant", tenant)
            .addKeyValue("entityKind", entityKind.name)
            .addKeyValue("entityRef", entityRef)
            .addKeyValue("principalKind", principalKind.name)
            .addKeyValue("result", result)
            .log(
                "flow context call callerService={} flowId={} tenant={} entityKind={} entityRef={} " +
                    "principalKind={} result={}",
                caller,
                flowId,
                tenant,
                entityKind.name,
                entityRef,
                principalKind.name,
                result,
            )
    }

    companion object {
        const val ISSUED = "ISSUED"
        const val TENANT_MISMATCH = "TENANT_MISMATCH"
        const val CALLER_NOT_SERVED = "CALLER_NOT_SERVED"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val SIGNING_UNAVAILABLE = "SIGNING_UNAVAILABLE"
        private val LOG = LoggerFactory.getLogger(IssueFlowContextOperation::class.java)
    }
}
