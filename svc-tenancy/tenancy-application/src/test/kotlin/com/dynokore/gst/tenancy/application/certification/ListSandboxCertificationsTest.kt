package com.dynokore.gst.tenancy.application.certification

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ListSandboxCertificationsTest {
    private val t1 = Instant.parse("2026-11-01T00:00:00Z")

    private fun entry(sequence: Long) =
        CertificationHistoryEntry(
            sequence,
            CertificationRecord("E1", CertificationOutcome.PASSED, "1.0.$sequence", null, "U1", t1),
        )

    private val stored = listOf(entry(1), entry(2), entry(3))

    /** Entity E1 with three runs; every call of either port is counted and logged. */
    private inner class Stores {
        val calls = mutableListOf<String>()

        val entities =
            CertifiableEntities { ref ->
                calls += "exists $ref"
                ref == "E1"
            }

        val history =
            CertificationHistory { ref, limit, after ->
                calls += "page $ref $limit $after"
                val rest = stored.filter { after == null || it.sequence > after.sequence }
                val taken = rest.take(limit)
                val last =
                    if (rest.size > limit) CertificationHistoryPosition(t1, taken.last().sequence) else null
                CertificationHistoryPage(taken, last)
            }
    }

    private val stores = Stores()
    private var transactions = 0

    private fun useCase(authority: ActionAuthority) =
        ListSandboxCertifications(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T {
                    transactions++
                    return block()
                }
            },
            PeriodAccess(authority, { "U1" }),
            stores.entities,
            stores.history,
        )

    private val holder =
        ActionAuthority { person, ref, action ->
            person == "U1" && ref in setOf("E1", "E9") && action == "sandbox-certification.record"
        }

    private fun page(outcome: ListCertificationsOutcome) =
        assertInstanceOf(ListCertificationsOutcome.Page::class.java, outcome).page

    @Test
    fun `INV-AC-29132 a holder reads every run once, a page at a time`() {
        val list = useCase(holder)
        val first = page(list.list("E1", 2, null))
        val second = page(list.list("E1", 2, first.last))
        assertEquals(stored, first.entries + second.entries)
        assertNull(second.last)
        assertEquals("page E1 2 ${first.last}", stores.calls.last())
    }

    @Test
    fun `INV-AC-29132 a non-holder and a failing authority are refused before any store read`() {
        val refused =
            listOf(
                ActionAuthority { _, _, _ -> false },
                ActionAuthority { _, _, action -> action == "sandbox-certification.records" },
                ActionAuthority { _, ref, _ -> ref == "E2" },
                ActionAuthority { _, _, _ -> error("replica down") },
            )
        refused.forEach { authority ->
            listOf("E1", "E9").forEach { ref ->
                assertInstanceOf(
                    ListCertificationsOutcome.Forbidden::class.java,
                    useCase(authority).list(ref, 10, null),
                )
            }
        }
        assertEquals(emptyList<String>(), stores.calls)
        assertEquals(0, transactions)
    }

    @Test
    fun `INV-AC-29132 a non-holder with a limit out of range is refused as forbidden`() {
        assertInstanceOf(
            ListCertificationsOutcome.Forbidden::class.java,
            useCase { _, _, _ -> false }.list("E1", 501, null),
        )
    }

    @Test
    fun `INV-AC-29132 an unknown entity is not found and its history is not read`() {
        assertEquals(ListCertificationsOutcome.NotFound, useCase(holder).list("E9", 10, null))
        assertEquals(listOf("exists E9"), stores.calls)
    }

    @Test
    fun `INV-AC-29132 a limit below 1 or above 500 is refused with no store read`() {
        listOf(Int.MIN_VALUE, -1, 0, 501, Int.MAX_VALUE).forEach {
            assertInstanceOf(ListCertificationsOutcome.Invalid::class.java, useCase(holder).list("E1", it, null))
        }
        assertEquals(emptyList<String>(), stores.calls)
    }

    @Test
    fun `INV-AC-29132 the limits 1 and 500 are accepted`() {
        assertEquals(1, page(useCase(holder).list("E1", 1, null)).entries.size)
        assertEquals(3, page(useCase(holder).list("E1", 500, null)).entries.size)
    }
}
