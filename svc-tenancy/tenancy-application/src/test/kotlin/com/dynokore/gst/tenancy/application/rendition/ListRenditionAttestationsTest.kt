package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.period.PeriodAccess
import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ListRenditionAttestationsTest {
    private val t1 = Instant.parse("2026-11-01T00:00:00Z")
    private val t2 = Instant.parse("2026-11-05T00:00:00Z")

    private fun entry(
        cls: String,
        seq: Long,
        at: Instant,
    ) = RenditionHistoryEntry(cls, seq, Renderer.SELF, "U$seq", at, AttestationState.CURRENT)

    /** In-memory history of entities E1 and E2; every store call is counted. */
    private class Store(
        val rows: Map<String, List<RenditionHistoryEntry>>,
    ) : RenditionEntities,
        RenditionAttestationHistory {
        var reads = 0

        override fun exists(entityRef: String): Boolean {
            reads++
            return entityRef in rows
        }

        override fun list(
            entityRef: String,
            documentClass: String,
        ): List<RenditionAttestationRecord> = error("not used")

        private fun follows(
            row: RenditionHistoryEntry,
            after: RenditionHistoryPosition,
        ) = row.attestedAt > after.attestedAt || (row.attestedAt == after.attestedAt && row.seq > after.seq)

        override fun page(
            entityRef: String,
            limit: Int,
            after: RenditionHistoryPosition?,
        ): RenditionHistoryPage {
            reads++
            val sorted =
                rows
                    .getValue(entityRef)
                    .sortedWith(compareBy({ it.attestedAt }, { it.seq }))
                    .filter { after == null || follows(it, after) }
            val taken = sorted.take(limit)
            val more = sorted.size > limit
            val last = if (more) taken.last().let { RenditionHistoryPosition(it.attestedAt, it.seq) } else null
            return RenditionHistoryPage(taken, last)
        }
    }

    private val e1 = listOf(entry("TAX_INVOICE", 3, t2), entry("CREDIT_NOTE", 2, t1), entry("TAX_INVOICE", 1, t1))
    private val store = Store(mapOf("E1" to e1, "E2" to listOf(entry("TAX_INVOICE", 1, t1))))

    private fun useCase(authority: ActionAuthority?) =
        ListRenditionAttestations(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            PeriodAccess({ person, ref, action -> authority?.holds(person, ref, action) ?: false }, { "U1" }),
            store,
            store,
        )

    private val granted = ActionAuthority { _, _, action -> action == RenditionPermissions.EVIDENCE_VIEW }

    private fun entries(outcome: ListRenditionOutcome) =
        assertInstanceOf(ListRenditionOutcome.Page::class.java, outcome).page

    @Test
    fun `INV-AC-27599 the page of E1 holds its three rows by attestedAt and seq and none of E2`() {
        val page = entries(useCase(granted).list("E1", 100, null))
        assertEquals(listOf(1L, 2L, 3L), page.entries.map { it.seq })
        assertEquals(listOf("TAX_INVOICE", "CREDIT_NOTE", "TAX_INVOICE"), page.entries.map { it.documentClass })
        assertNull(page.last)
    }

    @Test
    fun `INV-AC-27599 rows with the same attestedAt are ordered by seq`() {
        assertEquals(listOf(1L, 2L), entries(useCase(granted).list("E1", 100, null)).entries.take(2).map { it.seq })
    }

    @Test
    fun `INV-AC-27599 limit 2 then the cursor position gives 2 and 1 rows and the last page has no position`() {
        val first = entries(useCase(granted).list("E1", 2, null))
        assertEquals(2, first.entries.size)
        val second = entries(useCase(granted).list("E1", 2, first.last))
        assertEquals(listOf(3L), second.entries.map { it.seq })
        assertNull(second.last)
    }

    @Test
    fun `INV-AC-27599 no key a DEGRADED decision a null replica and an exception are Forbidden with no store read`() {
        val denied =
            listOf(
                ActionAuthority { _, _, _ -> false },
                ActionAuthority { _, _, action -> action == "other.view" },
                null,
                ActionAuthority { _, _, _ -> throw IllegalStateException("replica down") },
            )
        denied.forEach {
            assertInstanceOf(ListRenditionOutcome.Forbidden::class.java, useCase(it).list("E1", 10, null))
        }
        assertEquals(0, store.reads)
    }

    @Test
    fun `INV-AC-27599 an unknown entity is NotFound`() {
        assertEquals(ListRenditionOutcome.NotFound, useCase(granted).list("E9", 10, null))
    }

    @Test
    fun `INV-AC-27599 limit 0 and 501 are Invalid and 1 and 500 are accepted`() {
        listOf(0, 501, -1).forEach {
            assertInstanceOf(ListRenditionOutcome.Invalid::class.java, useCase(granted).list("E1", it, null))
        }
        assertEquals(1, entries(useCase(granted).list("E1", 1, null)).entries.size)
        assertEquals(3, entries(useCase(granted).list("E1", 500, null)).entries.size)
    }

    @Test
    fun `INV-AC-27599 the permission key is exactly rendition-evidence dot view`() {
        assertEquals("rendition-evidence.view", RenditionPermissions.EVIDENCE_VIEW)
    }
}
