package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.ListedIntakeOperation
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * API-18: the `nextCursor` of `listAggregationIntake` is a payload (tenant, principal, the three filters, the whole
 * keyset position of the last item, its intake operation included, an expiry 24 hours after issue) sealed by
 * [SignedCursorPayload], as [DrillDownCursors] does. The principal is the acting user ID, never a role. The instants
 * of the filter are bound, not their text, so the same instant in another offset is the same filter. [decode] answers
 * null for a cursor that is malformed, forged, altered, of another operation, issued for another tenant, principal or
 * filter, or expired: the caller answers 400.
 */
class IntakeListingCursors(
    secrets: SecretStore,
    private val clock: Clock,
) {
    /** What a cursor is bound to: the tenant, the principal and the filters; the sort is fixed by the version. */
    data class Scope(
        val tenant: String,
        val actor: String,
        val filter: IntakeListingFilter,
    ) {
        internal fun fields() =
            listOf(tenant, actor, filter.submittedBy, filter.recordedFrom.toString(), filter.recordedTo.toString())
    }

    private val payload = SignedCursorPayload(secrets)

    fun encode(
        last: IntakeListingPosition,
        scope: Scope,
    ): String {
        val position =
            listOf(
                last.sourceKey,
                last.recordedAt.toString(),
                last.sourceRevision.toString(),
                last.entityRef,
                last.intakeOperation.name,
            )
        val expires = clock.instant().plus(VALID_FOR).toString()
        return payload.seal(listOf(VERSION) + scope.fields() + position + expires)
    }

    fun decode(
        cursor: String,
        scope: Scope,
    ): IntakeListingPosition? {
        val fields = payload.open(cursor)?.takeIf { it.size == SIZE && it[0] == VERSION }
        val expires = fields?.let { runCatching { Instant.parse(it[EXPIRES]) }.getOrNull() }
        val bound = fields?.subList(1, 1 + SCOPE) == scope.fields()
        val live = expires != null && !clock.instant().isAfter(expires)
        return if (fields != null && bound && live) position(fields) else null
    }

    /** The position of fields whose tag is genuine, so they are as [encode] wrote them. */
    private fun position(fields: List<String>) =
        IntakeListingPosition(
            sourceKey = fields[SOURCE_KEY],
            recordedAt = Instant.parse(fields[RECORDED_AT]),
            sourceRevision = fields[SOURCE_REVISION].toLong(),
            entityRef = fields[ENTITY_REF],
            intakeOperation = ListedIntakeOperation.valueOf(fields[INTAKE_OPERATION]),
        )

    private companion object {
        const val VERSION = "intake-listing-v1"
        const val SCOPE = 5
        const val SOURCE_KEY = 6
        const val RECORDED_AT = 7
        const val SOURCE_REVISION = 8
        const val ENTITY_REF = 9
        const val INTAKE_OPERATION = 10
        const val EXPIRES = 11
        const val SIZE = 12
        val VALID_FOR: Duration = Duration.ofHours(24)
    }
}
