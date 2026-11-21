package com.dynokore.gst.notification.domain.alerting

/** The AccessAlertRaised code of an entity without an active tax.approver (INV-REQ-895). */
const val ACC_ORPHANED_APPROVER = "ACC-001"

/** The scope of an ACC-001 event is exactly this prefix followed by the entity reference. */
const val ENTITY_SCOPE_PREFIX = "entity:"

/** ACC-001 goes to the holders of both roles at once, with no escalation tier (INV-REQ-895, INV-REQ-1113). */
val ACCESS_ALERT_ROLES: List<String> = listOf(ROLE_TENANT_ADMIN, ROLE_SECURITY_OFFICER)
