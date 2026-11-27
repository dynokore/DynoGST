package com.dynokore.gst.aggregation.adapters.permission

/** Which entities of a listing the acting user may see (INV-Q-10027: a scoped permission, never a role name). */
fun interface EntityViewPermission {
    /**
     * The entities of [entityRefs] that [actor] holds `submission.view-status` on, one decision per entity; null when
     * the decisions are not available, so the caller refuses the whole request (INV-AC-28766).
     */
    fun permittedAmong(
        actor: String,
        entityRefs: Set<String>,
    ): Set<String>?
}
