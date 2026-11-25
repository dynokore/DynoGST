package com.dynokore.gst.canonical.application.document

/**
 * A recording whose identity (tenant, entity, direction, document type, issuer party, document number, fiscal year)
 * equals that of a recorded document is blocked with SUB-007 (BLOCK, TERMINAL, INV-AC-1768); no second Doc UUID exists.
 */
class DocumentIdentityConflict(
    cause: Throwable? = null,
) : IllegalStateException(CODE, cause) {
    val code: String = CODE

    companion object {
        /** The stable error code of the block; compared by equality. */
        const val CODE = "SUB-007"
    }
}
