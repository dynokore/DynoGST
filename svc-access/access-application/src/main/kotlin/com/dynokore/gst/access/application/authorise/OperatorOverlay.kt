package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.consultant.ConsultantGrantKeys
import com.dynokore.gst.access.application.elevation.ElevationKeys
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PlatformAuthorisation
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import java.time.Instant

/**
 * What an operator may do on top of its grants (INV-REQ-862 behaviours 1 and 3, INV-REQ-888 behaviour 2). Called inside
 * the transaction of `authorise`, after the grant decision; reads the records live, keeps no state (INV-REQ-1214).
 * - An ALLOW whose role is `deployment.operator` stands only inside an approved, unexpired diagnostic invitation of the
 *   same identity for that tenant; otherwise it is DENY ([OperatorAccess.NO_INVITATION]).
 * - A DENY of exactly the keys in [OperatorAccess.OPENED_BY_ELEVATION] becomes ALLOW inside an approved, unexpired
 *   elevation of the same identity for that tenant, and only while the identity still holds `elevation.request` on a
 *   live platform grant ([operatorLive], read in a platform-bound transaction, so a revoked or expired grant closes the
 *   elevation at once; NIST SP 800-53 AC-2, AC-6). Every other key, tenant or instant keeps the DENY.
 * - Exception: on the scope platform (no tenant) the keys in [OPEN_WITHOUT_INVITATION] stand without an invitation.
 * Each access it opens is audited (INV-AC-1930, INV-AC-7953).
 */
class OperatorOverlay(
    private val elevations: Elevations,
    private val audit: GrantAudit,
    private val platform: PlatformRead,
    private val grants: GrantRepository,
    private val matrix: PermissionMatrix,
) {
    /** Whether [principal] holds `elevation.request` on a live platform grant at [at]; call outside any transaction. */
    fun operatorLive(
        principal: IdentityRef,
        at: Instant,
    ): Boolean {
        val held = platform.read { grants.findByIdentity(principal) }
        return PlatformAuthorisation.decide(held, ElevationKeys.REQUEST, matrix, at) is Decision.Allow
    }

    @Suppress("LongParameterList") // the question of authorise plus the live platform answer
    fun apply(
        decision: Decision,
        principal: IdentityRef,
        permission: Permission,
        tenant: TenantRef?,
        at: Instant,
        operatorLive: Boolean = false,
    ): Decision =
        when {
            decision is Decision.Allow && decision.role == Role.DEPLOYMENT_OPERATOR -> {
                invitationRule(decision, principal, permission, tenant, at)
            }

            decision is Decision.Deny &&
                tenant != null &&
                permission in OperatorAccess.OPENED_BY_ELEVATION &&
                operatorLive &&
                OperatorAccess.elevated(elevations.findByRequester(principal), principal, tenant, at) -> {
                used("elevatedAccess", principal, permission, tenant)
                Decision.Allow(Role.PLATFORM_SUPPORT)
            }

            else -> {
                decision
            }
        }

    /** The invitation rule of an operator ALLOW; the keys of [OPEN_WITHOUT_INVITATION] on the platform are exempt. */
    private fun invitationRule(
        decision: Decision.Allow,
        principal: IdentityRef,
        permission: Permission,
        tenant: TenantRef?,
        at: Instant,
    ): Decision {
        if (tenant == null && permission in OPEN_WITHOUT_INVITATION) return decision
        val records = elevations.findByRequester(principal)
        val invited =
            if (tenant == null) {
                OperatorAccess.invitedHere(records, principal, at)
            } else {
                OperatorAccess.invited(records, principal, tenant, at)
            }
        return if (invited) {
            used("operatorDiagnosticAccess", principal, permission, tenant)
            decision
        } else {
            Decision.Deny(OperatorAccess.NO_INVITATION)
        }
    }

    companion object {
        /**
         * The exact keys an operator holds on the scope platform without an invitation (INV-REQ-888, owner ruling
         * 2026-11-22, option A); compared by equality, every other key keeps the invitation rule (NIST SP 800-53 AC-6).
         * The request endpoint audits each request itself (INV-AC-1942), so no second audit entry is written here.
         */
        val OPEN_WITHOUT_INVITATION: Set<Permission> = setOf(ConsultantGrantKeys.REQUEST)
    }

    private fun used(
        action: String,
        principal: IdentityRef,
        permission: Permission,
        tenant: TenantRef?,
    ) = audit.record(
        GrantAuditEntry(principal.value, action, "tenant:${tenant?.value ?: "platform"}", permission.value, null),
    )
}
