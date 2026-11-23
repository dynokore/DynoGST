package com.dynokore.gst.tenancy.application.rendition

/** The permission keys of the rendition evidence reads (ruling of INV-REQ-1036, option A); decided per request. */
object RenditionPermissions {
    /** Read the attestation history of an entity: one READ key held through `AuthorityReplica.decide`. */
    const val EVIDENCE_VIEW = "rendition-evidence.view"
}
