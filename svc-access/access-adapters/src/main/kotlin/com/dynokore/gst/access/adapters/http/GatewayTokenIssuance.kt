package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.principal.PrincipalTenantResolver
import com.dynokore.gst.access.application.token.CertificationPolicy
import com.dynokore.gst.access.application.token.IssueAccessTokenUseCase
import com.dynokore.gst.access.application.token.IssuedToken
import com.dynokore.gst.access.application.token.TenantEnvironments
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The outcome of `issueAccessToken`: a token, or the reason code of the refusal. */
sealed interface TokenDecision {
    data class Issued(
        val token: IssuedToken,
    ) : TokenDecision

    data class Refused(
        val reasonCode: String,
    ) : TokenDecision
}

/**
 * The adapter of `issueAccessToken`: the proof is decided by [GatewayAuthentication] (tenant resolved by the claim
 * port, INV-Q-8982 A; a revoked or unknown principal is REFUSED there), and only an authenticated principal reaches the
 * signer. A key that cannot sign propagates as `SigningKeyUnavailable` and writes no entry. Every answered outcome
 * writes one audit entry (action `issueAccessToken`, actor the principal's ID, detail `TOKEN_ISSUED` or
 * `REFUSED:<reasonCode>`; never the proof, the method or the token) in a transaction of its own under the principal's
 * tenant; a refusal of an unknown principal has no tenant, so its entry is written under the platform binding as the
 * key registry's insert is (INV-Q-8785 A). INV-REQ-1384, INV-Q-10647.
 */
@Suppress("LongParameterList") // one collaborator per port
class GatewayTokenIssuance(
    val authentication: GatewayAuthentication,
    private val issuer: IssueAccessTokenUseCase,
    private val environments: TenantEnvironments,
    private val resolver: PrincipalTenantResolver,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val audit: GrantAudit,
    private val workload: String,
) {
    init {
        require(workload.isNotBlank()) { "the actor of the gateway call must be set" }
    }

    fun issue(
        principalRef: String,
        method: CredentialMethod,
        proof: String,
    ): TokenDecision {
        var tenant: TenantId? = null
        val decision =
            when (val answer = authentication.authenticate(principalRef, method, proof)) {
                is AuthenticationDecision.Refused -> {
                    tenant = platform.bindPlatform(workload) { resolver.tenantOf(principalRef) }
                    TokenDecision.Refused(answer.reasonCode)
                }

                is AuthenticationDecision.Authenticated -> {
                    val principal = answer.principal
                    tenant = TenantId.of(principal.tenant.value)
                    val environment = environments.of(principal.tenant)
                    if (CertificationPolicy.permits(principal.type, environment)) {
                        TokenDecision.Issued(issuer.issue(principal, environment))
                    } else {
                        TokenDecision.Refused(UNKNOWN_PRINCIPAL)
                    }
                }
            }
        val detail =
            when (decision) {
                is TokenDecision.Issued -> OUTCOME_ISSUED
                is TokenDecision.Refused -> REFUSED_PREFIX + decision.reasonCode
            }
        record(tenant, principalRef, detail)
        return decision
    }

    private fun record(
        tenant: TenantId?,
        principalRef: String,
        detail: String,
    ) {
        val write = {
            tx.inTransaction {
                audit.record(GrantAuditEntry(principalRef, OPERATION, "principal:$principalRef", detail, null))
            }
        }
        if (tenant == null) platform.bindPlatform(workload, write) else tenants.bind(tenant, workload, write)
    }

    private companion object {
        const val UNKNOWN_PRINCIPAL = "UNKNOWN"
        const val OPERATION = "issueAccessToken"
        const val OUTCOME_ISSUED = "TOKEN_ISSUED"
        const val REFUSED_PREFIX = "REFUSED:"
    }
}
