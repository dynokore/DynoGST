package com.dynokore.gst.canonical.adapters.permission

/** The `submission.view-status` decision of one request over the entities it would answer (INV-Q-10027). */
fun interface EntityViewPermission {
    /**
     * The members of [entityRefs] that [actor] is permitted for in the bound tenant, or null when the decision of any
     * one of them is unavailable. Null refuses the whole request; it is never read as an empty or partial set.
     */
    fun permittedAmong(
        actor: String,
        entityRefs: Set<String>,
    ): Set<String>?
}
