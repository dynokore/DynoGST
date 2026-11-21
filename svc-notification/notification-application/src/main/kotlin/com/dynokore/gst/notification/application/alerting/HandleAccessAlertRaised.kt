package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.ACCESS_ALERT_ROLES
import com.dynokore.gst.notification.domain.alerting.ACC_ORPHANED_APPROVER
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.ENTITY_SCOPE_PREFIX
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

data class AccessAlertDraft(
    val tenant: TenantId,
    val alertClass: AlertClass = AlertClass.SECURITY,
    val trigger: String = ACC_ORPHANED_APPROVER,
    val entityRef: String,
    val raisedAt: Instant,
    val recipients: List<String>,
)

interface AccessAlertStore {
    /** True when a new open alert was stored, false when one is already open for the key. */
    fun raise(draft: AccessAlertDraft): Boolean
}

enum class AccessAlertResult { RAISED, ALREADY_OPEN, IGNORED_OTHER_CODE }

/** No holder of tenant.admin or security.officer exists; nothing was written, the caller retries or dead-letters. */
class AccessAlertNotDelivered(
    val tenant: TenantId,
) : RuntimeException("No recipient for access alert in tenant ${tenant.value}")

/** Handles AccessAlertRaised ACC-001 (INV-REQ-895): one SECURITY alert to the holders of both roles, no tier. */
class HandleAccessAlertRaised(
    private val roles: RoleHolders,
    private val store: AccessAlertStore,
    private val clock: Clock,
) {
    fun handle(
        tenant: TenantId,
        payload: Map<String, Any?>,
    ): AccessAlertResult {
        val code = payload["accCode"] as? String
        if (code != ACC_ORPHANED_APPROVER) return AccessAlertResult.IGNORED_OTHER_CODE
        val entityRef = entityRef(payload["scope"])
        val recipients =
            ACCESS_ALERT_ROLES
                .flatMap { roles.holders(tenant, it) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        if (recipients.isEmpty()) throw AccessAlertNotDelivered(tenant)
        val draft = AccessAlertDraft(tenant, entityRef = entityRef, raisedAt = clock.instant(), recipients = recipients)
        val stored = store.raise(draft)
        return if (stored) AccessAlertResult.RAISED else AccessAlertResult.ALREADY_OPEN
    }

    private fun entityRef(scope: Any?): String {
        val text = scope as? String
        if (text == null || !text.startsWith(ENTITY_SCOPE_PREFIX)) {
            throw ContractViolation("scope must be entity:<reference>")
        }
        val ref = text.removePrefix(ENTITY_SCOPE_PREFIX)
        if (ref.isBlank() || ref.length > MAX_ENTITY_REF) {
            throw ContractViolation("scope reference must be 1 to $MAX_ENTITY_REF characters")
        }
        return ref
    }

    private companion object {
        const val MAX_ENTITY_REF = 64
    }
}
