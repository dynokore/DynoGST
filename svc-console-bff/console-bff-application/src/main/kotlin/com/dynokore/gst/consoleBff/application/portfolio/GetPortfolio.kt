package com.dynokore.gst.consoleBff.application.portfolio

import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.gst.consoleBff.domain.portfolio.PeriodFact
import com.dynokore.gst.consoleBff.domain.portfolio.PortfolioRow
import com.dynokore.gst.consoleBff.domain.portfolio.Readiness
import com.dynokore.gst.consoleBff.domain.portfolio.Verdict
import com.dynokore.gst.consoleBff.domain.portfolio.buildPortfolio
import java.time.LocalDate
import java.util.Base64

data class PortfolioQuery(
    val tenantId: String,
    val principalId: String,
    val limit: Int,
    val cursor: String?,
)

sealed interface PortfolioResult {
    /** [periodRefs] are the columns of the whole grant scope; [rows] are one page. */
    data class Listed(
        val periodRefs: List<String>,
        val rows: List<PortfolioRow>,
        val nextCursor: String?,
    ) : PortfolioResult

    data object BadCursor : PortfolioResult

    data object BadLimit : PortfolioResult
}

/**
 * The getPortfolio read (INV-REQ-904): cells built from the stored facts alone, no downstream call,
 * scope decided per request, keyset paging in the order of nearest due date. [maxLimit] is a parameter.
 */
class GetPortfolio(
    private val scope: EntityScope,
    private val store: PortfolioStore,
    private val maxLimit: Int,
) {
    fun list(query: PortfolioQuery): PortfolioResult {
        val after = query.cursor?.let { PortfolioCursor.decode(it) }
        return when {
            query.limit < 1 || query.limit > maxLimit -> PortfolioResult.BadLimit
            query.cursor != null && after == null -> PortfolioResult.BadCursor
            else -> page(query, after)
        }
    }

    private fun page(
        query: PortfolioQuery,
        after: PortfolioKey?,
    ): PortfolioResult.Listed {
        val entities = scope.entitiesOf(query.tenantId, query.principalId)
        if (entities.isEmpty()) return PortfolioResult.Listed(emptyList(), emptyList(), null)
        val stored = store.facts(query.tenantId, entities).filter { it.period.entityRef in entities }
        val facts = stored.groupBy({ it.period.entityRef }, { toFact(it) })
        val grid = buildPortfolio(facts)
        val keyed = grid.rows.map { it to keyOf(it, facts.getValue(it.entityRef)) }
        val remaining = keyed.filter { after == null || compare(it.second, after) > 0 }
        val rows = remaining.take(query.limit)
        val next = rows.lastOrNull()?.takeIf { remaining.size > query.limit }?.let { PortfolioCursor.encode(it.second) }
        return PortfolioResult.Listed(grid.periodRefs, rows.map { it.first }, next)
    }

    private fun toFact(s: StoredFact) =
        PeriodFact(
            periodRef = s.period.periodRef,
            state = s.period.state,
            filingDueDate = s.period.filingDueDate,
            filingDate = s.period.filingDate,
            extensionGranted = s.period.extensionGranted,
            readiness = s.readiness?.let { toReadiness(it) },
        )

    private fun toReadiness(r: StoredReadiness): Readiness =
        when (r.verdict) {
            Verdict.READY -> Readiness.Ready
            Verdict.BLOCKED -> r.blockedCount?.let { Readiness.Blocked(it) } ?: Readiness.Unavailable
            Verdict.UNABLE_TO_DETERMINE -> Readiness.Unavailable
        }

    /** The same order as [buildPortfolio]: nearest unfiled due date (none last), then entity. */
    private fun keyOf(
        row: PortfolioRow,
        facts: List<PeriodFact>,
    ) = PortfolioKey(facts.filter { it.filingDate == null }.minOfOrNull { it.filingDueDate }, row.entityRef)

    private fun compare(
        a: PortfolioKey,
        b: PortfolioKey,
    ): Int =
        compareBy<PortfolioKey> { it.nearestDue == null }
            .thenBy { it.nearestDue }
            .thenBy { it.entityRef }
            .compare(a, b)
}

internal data class PortfolioKey(
    val nearestDue: LocalDate?,
    val entityRef: String,
)

/** Opaque cursor: base64url, no padding, of `v1|<date or ->|<entityRef>`; unsigned, scope applies again per request. */
internal object PortfolioCursor {
    private const val VERSION = "v1"
    private const val NO_DATE = "-"
    private const val FIELDS = 3

    fun encode(key: PortfolioKey): String {
        val text = "$VERSION|${key.nearestDue?.toString() ?: NO_DATE}|${key.entityRef}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    fun decode(text: String): PortfolioKey? {
        val key = parse(text) ?: return null
        return key.takeIf { encode(it) == text }
    }

    private fun parse(text: String): PortfolioKey? =
        runCatching {
            val raw = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
            val parts = raw.split('|', limit = FIELDS)
            if (parts.size != FIELDS || parts[0] != VERSION) return null
            PortfolioKey(if (parts[1] == NO_DATE) null else LocalDate.parse(parts[1]), parts[2])
        }.getOrNull()
}
