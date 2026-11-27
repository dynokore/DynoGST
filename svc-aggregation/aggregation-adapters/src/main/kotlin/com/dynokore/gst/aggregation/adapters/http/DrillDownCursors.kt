package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The secret path of the HMAC key of the `nextCursor` of `getAggregateDrillDown`. */
const val DRILL_DOWN_CURSOR_KEY_SECRET = "aggregation/drill-down-cursor-key"

/**
 * API-18: the `nextCursor` of `getAggregateDrillDown` is a payload (tenant, principal, aggregate, the sort key of the
 * last item, an expiry 24 hours after issue) sealed by [SignedCursorPayload]. When the key cannot be read through
 * [SecretStore] (STK-29), [SecretNotFound] propagates and the controller answers 500, so no page carries a cursor.
 * [decode] answers null for a cursor that is malformed, forged, altered, issued for another tenant, principal or
 * aggregate, or expired: the caller answers 400.
 */
class DrillDownCursors(
    secrets: SecretStore,
    private val clock: Clock,
) {
    /** What a cursor is bound to: the principal and the filter (the aggregate); the sort is fixed by the version. */
    data class Scope(
        val tenant: String,
        val actor: String,
        val aggregateId: String,
    ) {
        internal fun fields() = listOf(tenant, actor, aggregateId)
    }

    private val payload = SignedCursorPayload(secrets)

    fun encode(
        last: DrillDownPosition,
        scope: Scope,
    ): String {
        val expires = clock.instant().plus(VALID_FOR)
        return payload.seal(
            listOf(VERSION) + scope.fields() + listOf(last.underlyingRef, last.transactionRef, expires.toString()),
        )
    }

    fun decode(
        cursor: String,
        scope: Scope,
    ): DrillDownPosition? {
        val fields = payload.open(cursor)?.takeIf { it.size == SIZE && it[0] == VERSION }
        val expires = fields?.let { runCatching { Instant.parse(it[EXPIRES]) }.getOrNull() }
        val bound = fields?.subList(1, 1 + SCOPE) == scope.fields()
        val live = expires != null && !clock.instant().isAfter(expires)
        return if (fields != null && bound && live) DrillDownPosition(fields[UNDERLYING], fields[REF]) else null
    }

    private companion object {
        const val VERSION = "v1"
        const val SCOPE = 3
        const val UNDERLYING = 4
        const val REF = 5
        const val EXPIRES = 6
        const val SIZE = 7
        val VALID_FOR: Duration = Duration.ofHours(24)
    }
}
