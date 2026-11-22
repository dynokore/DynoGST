package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.gst.notification.application.delivery.TemplateVersions

/**
 * The closed set of templates access-svc requests today (INV-REQ-2762): `signed-link`, `signed-link-passcode` and
 * `retention-notice`, each at version 1. Any other template is unknown and its record is FAILED TEMPLATE_UNKNOWN.
 */
class ClosedTemplateVersions : TemplateVersions {
    override fun versionOf(templateId: String): String? = VERSIONS[templateId]

    companion object {
        const val SIGNED_LINK = "signed-link"
        const val SIGNED_LINK_PASSCODE = "signed-link-passcode"
        const val RETENTION_NOTICE = "retention-notice"
        val VERSIONS: Map<String, String> =
            mapOf(SIGNED_LINK to "1", SIGNED_LINK_PASSCODE to "1", RETENTION_NOTICE to "1")
    }
}
