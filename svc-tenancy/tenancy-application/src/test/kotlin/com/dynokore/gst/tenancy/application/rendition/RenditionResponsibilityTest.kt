package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class MemStore : RenditionAttestationStore {
    val rows = linkedMapOf<Pair<String, String>, RenditionAttestation>()
    val sequences = mutableMapOf<String, Long>()
    var changes = 0
    var puts = 0

    override fun put(attestation: RenditionAttestation) {
        puts++
        rows[attestation.entityRef to attestation.documentClass] = attestation
    }

    override fun find(
        entityRef: String,
        documentClass: String,
    ) = rows[entityRef to documentClass]

    override fun setStateForEntity(
        entityRef: String,
        state: AttestationState,
    ): Int {
        var n = 0
        for ((k, v) in rows.entries.toList()) {
            if (k.first == entityRef && v.state != state) {
                rows[k] = v.copy(state = state)
                n++
            }
        }
        changes += n
        return n
    }

    override fun lastSequence(entityRef: String) = sequences[entityRef]

    override fun storeSequence(
        entityRef: String,
        sequence: Long,
    ) {
        sequences[entityRef] = sequence
    }
}

private val direct =
    object : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

class RenditionResponsibilityTest {
    private val store = MemStore()
    private val record = RecordRenditionResponsibility(direct, { it == "E1" || it == "E2" }, store)
    private val finding = ApplyRenditionFinding(direct, store)

    private fun rec(
        entity: String = "E1",
        cls: String = "TYPE_1A",
        renderer: String? = "SELF",
        by: String = "u-1",
        caller: String = "u-1",
    ) = record.handle(caller, entity, cls, renderer, by)

    @Test
    fun `INV-AC-461 attestedBy equal to the caller is stored as CURRENT and a finding requests it again`() {
        val out = rec(renderer = "AGENT") as RecordRenditionOutcome.Recorded
        assertEquals(
            RenditionAttestation("E1", "TYPE_1A", Renderer.AGENT, "u-1", AttestationState.CURRENT),
            out.attestation,
        )
        assertEquals(out.attestation, store.find("E1", "TYPE_1A"))
        finding.handle(RenditionFindingChanged("E1", true, 1))
        assertEquals(AttestationState.REQUESTED_AGAIN, store.find("E1", "TYPE_1A")!!.state)
    }

    @Test
    fun `INV-AC-461 attestedBy different from the caller is SRT-004 and stores nothing`() {
        assertEquals(RecordRenditionOutcome.Forbidden, rec(by = "u-2"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `renderer AGGREGATORS look-alike is rejected and stores nothing`() {
        assertTrue(rec(renderer = "AGGREGATORS") is RecordRenditionOutcome.Invalid)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-461 open true on an entity with two classes sets both REQUESTED_AGAIN`() {
        rec(cls = "TYPE_1A")
        rec(cls = "TYPE_1B")
        assertEquals(FindingResult.APPLIED, finding.handle(RenditionFindingChanged("E1", true, 1)))
        assertEquals(AttestationState.REQUESTED_AGAIN, store.find("E1", "TYPE_1A")!!.state)
        assertEquals(AttestationState.REQUESTED_AGAIN, store.find("E1", "TYPE_1B")!!.state)
    }

    @Test
    fun `the same event twice is one change`() {
        rec(cls = "TYPE_1A")
        assertEquals(FindingResult.APPLIED, finding.handle(RenditionFindingChanged("E1", true, 1)))
        assertEquals(FindingResult.IGNORED, finding.handle(RenditionFindingChanged("E1", true, 1)))
        assertEquals(1, store.changes)
    }

    @Test
    fun `an event with a lower sequence than the stored one is ignored`() {
        rec(cls = "TYPE_1A")
        finding.handle(RenditionFindingChanged("E1", false, 5))
        assertEquals(FindingResult.IGNORED, finding.handle(RenditionFindingChanged("E1", true, 4)))
        assertEquals(AttestationState.CURRENT, store.find("E1", "TYPE_1A")!!.state)
        assertEquals(5L, store.sequences["E1"])
    }

    @Test
    fun `an event with open false changes no state`() {
        rec(cls = "TYPE_1A")
        assertEquals(FindingResult.APPLIED, finding.handle(RenditionFindingChanged("E1", false, 1)))
        assertEquals(AttestationState.CURRENT, store.find("E1", "TYPE_1A")!!.state)
        assertEquals(0, store.changes)
    }

    @Test
    fun `a record call after the event sets CURRENT for that class only`() {
        rec(cls = "TYPE_1A")
        rec(cls = "TYPE_1B")
        finding.handle(RenditionFindingChanged("E1", true, 1))
        rec(cls = "TYPE_1A")
        assertEquals(AttestationState.CURRENT, store.find("E1", "TYPE_1A")!!.state)
        assertEquals(AttestationState.REQUESTED_AGAIN, store.find("E1", "TYPE_1B")!!.state)
    }

    @Test
    fun `an event for an entity without classes creates no row`() {
        rec(entity = "E2", cls = "TYPE_1A")
        assertEquals(FindingResult.APPLIED, finding.handle(RenditionFindingChanged("E1", true, 1)))
        assertTrue(store.rows.keys.none { it.first == "E1" })
        assertNull(store.find("E1", "TYPE_1A"))
        assertEquals(AttestationState.CURRENT, store.find("E2", "TYPE_1A")!!.state)
    }

    @Test
    fun `an unknown entity is not found and stores nothing`() {
        assertEquals(RecordRenditionOutcome.NotFound, rec(entity = "E9"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-27909 each of the four document classes is recorded`() {
        listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3").forEach {
            assertTrue(rec(cls = it) is RecordRenditionOutcome.Recorded, it)
            assertEquals(it, store.find("E1", it)!!.documentClass)
        }
        assertEquals(4, store.puts)
    }

    @Test
    fun `INV-AC-27909 any other document class is SRT-001 and the store is not called`() {
        listOf("TAX_INVOICE", "tax_invoice", "TYPE_2 ", "").forEach {
            assertTrue(rec(cls = it) is RecordRenditionOutcome.Invalid, "'$it'")
        }
        assertEquals(0, store.puts)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-27909 the refusal order is attester then renderer then class then entity`() {
        assertEquals(RecordRenditionOutcome.Forbidden, rec(cls = "X", renderer = "X", by = "u-2", entity = "E9"))
        assertEquals(
            RecordRenditionOutcome.Invalid("renderer must be SELF, AGENT or AGGREGATOR"),
            rec(cls = "X", renderer = "X", entity = "E9"),
        )
        assertEquals(RecordRenditionOutcome.Invalid("documentClass is required"), rec(cls = " ", entity = "E9"))
        assertTrue(rec(cls = "X", entity = "E9") is RecordRenditionOutcome.Invalid)
        assertEquals(RecordRenditionOutcome.NotFound, rec(entity = "E9"))
        assertEquals(0, store.puts)
    }
}
