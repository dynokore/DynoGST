package com.dynokore.gst.tenancy.application.period

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

private val GO_LIVE: LocalDate = LocalDate.of(2026, 2, 10)
private val Q1 = SubmissionPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
private val Q2 = SubmissionPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))

private class MemoryScopeStore(
    private val rows: Map<String, SubmissionScopeRecord>,
) : SubmissionScopeStore {
    override fun find(entityRef: String): SubmissionScopeRecord? = rows[entityRef]
}

private val USE_CASE =
    GetSubmissionScope(
        MemoryScopeStore(
            mapOf(
                "ENT-1" to SubmissionScopeRecord(GO_LIVE, listOf(Q1, Q2)),
                "ENT-2" to SubmissionScopeRecord(null, emptyList()),
            ),
        ),
    )

private fun done(
    ref: String,
    today: LocalDate,
) = USE_CASE.get(ref, today) as SubmissionScopeOutcome.Done

class SubmissionScopeTest {
    @Test
    fun `INV-AC-28053 the answer carries the stored go-live date`() {
        // a today far from the go-live date: the answer is the stored date, not the clock date
        assertEquals(GO_LIVE, done("ENT-1", LocalDate.of(2026, 5, 20)).activatedOn)
    }

    @Test
    fun `INV-AC-28053 the answer carries the current period start and end`() {
        assertEquals(Q2, done("ENT-1", LocalDate.of(2026, 5, 20)).current)
    }

    @Test
    fun `INV-AC-28053 an entity with no go-live date and no current period answers neither member`() {
        val out = done("ENT-2", LocalDate.of(2026, 5, 20))
        assertNull(out.activatedOn)
        assertNull(out.current)
    }

    @Test
    fun `INV-AC-28053 the last day of the current period is inside it and the day after is not`() {
        assertEquals(Q1, done("ENT-1", Q1.end).current)
        assertEquals(Q2, done("ENT-1", Q1.end.plusDays(1)).current)
        assertEquals(Q1, done("ENT-1", Q1.start).current)
        assertNull(done("ENT-1", Q2.end.plusDays(1)).current)
    }

    @Test
    fun `INV-AC-28053 an unknown entity is NotFound`() {
        assertEquals(SubmissionScopeOutcome.NotFound, USE_CASE.get("ENT-9", LocalDate.of(2026, 5, 20)))
    }

    @Test
    fun `INV-AC-28053 the permission key is transaction ingest`() {
        assertEquals("transaction.ingest", SubmissionScopePermissions.READ)
    }
}
