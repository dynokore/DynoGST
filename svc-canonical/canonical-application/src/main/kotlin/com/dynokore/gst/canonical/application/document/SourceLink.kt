package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.source.SourceKey

/**
 * The event that produced a document version, stored with the version row in the transaction that writes it
 * (INV-REQ-1191). The caller states it for every write; no version inherits it from the content it copies or
 * derives from, so it is not a member of `NewDocument` or `NewVersion`.
 */
sealed interface SourceLink {
    /** The key of a `source_event` row that the same transaction holds or inserts. */
    data class Recorded(
        val key: SourceKey,
    ) : SourceLink

    /**
     * A consumed event that has no `source_event` row of its own: the store writes one per version it writes, keyed
     * by [sourceSystem], the CloudEvents id [eventId] and the Doc UUID.
     */
    data class Consumed(
        val sourceSystem: String,
        val eventId: String,
    ) : SourceLink {
        init {
            require(sourceSystem.isNotBlank()) { "source system is blank" }
            require(eventId.isNotBlank()) { "event id is blank" }
        }
    }
}
