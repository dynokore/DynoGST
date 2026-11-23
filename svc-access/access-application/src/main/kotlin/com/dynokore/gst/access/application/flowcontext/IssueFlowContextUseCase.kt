package com.dynokore.gst.access.application.flowcontext

import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/** Lifetime of a flow context in seconds. access-svc owns it; the caller never sends it (owner 2026-11-18, A). */
const val FLOW_CONTEXT_LIFETIME_SECONDS: Long = 900L

/**
 * Kind of the originating principal: the closed vocabulary of `flow-context.schema.json` (RFC 8725); the member name is
 * the wire value. INV-T-24991 maps the generated enum to this one and tests the name sets for equality.
 */
enum class FlowPrincipalKind { HUMAN, CONSENT_AGENT, MACHINE, OPERATOR_SESSION, SIGNATORY_LINK }

enum class FlowEntityKind { ENTITY, TENANT_WIDE }

data class FlowEntity(
    val kind: FlowEntityKind,
    val ref: String? = null,
)

/** The claims signed into the flow context. */
data class FlowContextClaims(
    val tenant: TenantId,
    val entity: FlowEntity,
    val principalKind: FlowPrincipalKind,
    val callerService: String,
    val flowId: String,
    val iat: Long,
    val exp: Long,
)

/** Signs flow-context claims; throws [SigningKeyUnavailable] when no key is usable. */
fun interface FlowContextSigner {
    fun sign(claims: FlowContextClaims): String
}

sealed interface IssueFlowContextResult {
    data class Issued(
        val compact: String,
        val expiresAt: Long,
    ) : IssueFlowContextResult

    data object CallerNotServed : IssueFlowContextResult

    data object InvalidRequest : IssueFlowContextResult

    data object SigningUnavailable : IssueFlowContextResult
}

/** Issues a flow context after the entry decision. Stores nothing; the verified caller is read by the adapter. */
class IssueFlowContextUseCase(
    private val signer: FlowContextSigner,
    private val clock: Clock,
    allowedCallers: Set<String>,
) {
    /** The generated callers of the operation, copied so that a later change of the argument has no effect. */
    private val allowedCallers: Set<String> = allowedCallers.toSet()

    init {
        require(this.allowedCallers.isNotEmpty()) { "the allowed caller set must not be empty" }
    }

    @Suppress("LongParameterList")
    fun issue(
        callerService: String?,
        tenant: TenantId,
        entityKind: FlowEntityKind,
        entityRef: String?,
        principalKind: FlowPrincipalKind,
        flowId: String,
    ): IssueFlowContextResult {
        val caller = callerService?.takeIf { it in allowedCallers }
        return when {
            caller == null -> IssueFlowContextResult.CallerNotServed
            tenant.isPlatform -> IssueFlowContextResult.InvalidRequest
            !validEntity(entityKind, entityRef) || !validText(flowId) -> IssueFlowContextResult.InvalidRequest
            else -> sign(tenant, FlowEntity(entityKind, entityRef), principalKind, caller, flowId)
        }
    }

    private fun validText(value: String): Boolean = value.isNotBlank() && value.length <= MAX_TEXT_LENGTH

    /** ENTITY needs a ref of 1 to 64 characters (not blank); TENANT_WIDE carries none. */
    private fun validEntity(
        kind: FlowEntityKind,
        ref: String?,
    ): Boolean =
        when (kind) {
            FlowEntityKind.ENTITY -> ref != null && validText(ref)
            FlowEntityKind.TENANT_WIDE -> ref == null
        }

    private fun sign(
        tenant: TenantId,
        entity: FlowEntity,
        principalKind: FlowPrincipalKind,
        caller: String,
        flowId: String,
    ): IssueFlowContextResult {
        val iat = clock.instant().epochSecond
        val claims =
            FlowContextClaims(tenant, entity, principalKind, caller, flowId, iat, iat + FLOW_CONTEXT_LIFETIME_SECONDS)
        return try {
            IssueFlowContextResult.Issued(signer.sign(claims), claims.exp)
        } catch (_: SigningKeyUnavailable) {
            IssueFlowContextResult.SigningUnavailable
        }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 64
    }
}
