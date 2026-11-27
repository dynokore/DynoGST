package com.dynokore.gst.canonical.adapters.http

import com.dynokore.gst.canonical.application.exposure.ExposurePosition
import com.dynokore.gst.canonical.application.exposure.ExposureWindow

/** What a cursor of `listIngestedDocuments` is bound to besides its sort: tenant, acting user ID and filters. */
data class CursorBinding(
    val tenant: String,
    val userId: String,
    val window: ExposureWindow,
)

/**
 * The opaque, integrity-protected cursor of `listIngestedDocuments` (API-18). The composition root implements it:
 * the MAC comes from the platform crypto module (CS-53).
 */
interface IngestedDocumentCursors {
    /** The token for the page after [last]. Without a usable key no token is issued: the call throws. */
    fun issue(
        binding: CursorBinding,
        last: ExposurePosition,
    ): String

    /** The position [token] names, or null when it is malformed, altered, expired or issued for another [binding]. */
    fun open(
        token: String,
        binding: CursorBinding,
    ): ExposurePosition?
}
