package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.application.request.DirectTx
import com.dynokore.gst.masterdata.application.request.FakeParties
import com.dynokore.gst.masterdata.application.request.MutableClock
import com.dynokore.gst.masterdata.application.request.PARTY
import com.dynokore.gst.masterdata.application.request.Recorder
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-T-27569 (INV-REQ-1010 behaviour 4): a repeat of a bulk resolution reports no change and writes nothing. */
class BulkResolveTest {
    private class FakeOperations : BulkResolveOperations {
        val seen = mutableSetOf<Pair<UUID, String>>()

        override fun claim(
            operationId: UUID,
            actor: String,
        ): Boolean = seen.add(operationId to actor)
    }

    private val x = UUID.fromString("0b1b2c3d-0000-4000-8000-0000000000a1")
    private val docs = (1..3).map { UUID.fromString("0a1b2c3d-0000-4000-8000-00000000000$it") }
    private val parties = FakeParties()
    private val recorder = Recorder()
    private val subject =
        BulkResolve(
            DirectTx,
            RecordCounterparty(DirectTx, parties, MutableClock(), recorder),
            FakeOperations(),
        )
    private val change =
        CounterpartyChange(listOf(IdentifierInput(IdentifierScheme.UEN, "201912345K")), null, null, null, null)

    private fun command(documents: List<UUID> = docs) =
        RecordCommand("ENT-1", PARTY, change, "EV-1", WriteOrigin.OPERATOR, documents)

    @Test
    fun `INV-AC-27567 the first run reports 3 resolved and appends one version and publishes one event`() {
        val outcome = subject.handle(x, "user-1", command())
        assertEquals(BulkResolveOutcome(3, 0), outcome)
        assertEquals(1, parties.versions.size)
        assertEquals(1, recorder.resolved.size)
    }

    @Test
    fun `INV-AC-27567 the repeat with the same operation id reports 3 unchanged and writes nothing`() {
        subject.handle(x, "user-1", command())
        val versions = parties.versions.size
        val published = recorder.resolved.size
        val outcome = subject.handle(x, "user-1", command())
        assertEquals(BulkResolveOutcome(0, 3), outcome)
        assertEquals(versions, parties.versions.size)
        assertEquals(published, recorder.resolved.size)
    }

    @Test
    fun `INV-AC-27567 a new operation id with the same command is a new resolution`() {
        subject.handle(x, "user-1", command())
        val versions = parties.versions.size
        val outcome = subject.handle(UUID.fromString("0b1b2c3d-0000-4000-8000-0000000000a2"), "user-1", command())
        assertEquals(BulkResolveOutcome(3, 0), outcome)
        assertEquals(versions + 1, parties.versions.size)
    }

    @Test
    fun `INV-AC-27567 a document listed twice counts once on the first run and on the repeat`() {
        val twice = command(listOf(docs[0], docs[0]))
        assertEquals(BulkResolveOutcome(1, 0), subject.handle(x, "user-1", twice))
        assertEquals(BulkResolveOutcome(0, 1), subject.handle(x, "user-1", twice))
    }

    @Test
    fun `INV-AC-27567 the same operation id with another actor is not a repeat`() {
        subject.handle(x, "user-1", command())
        assertEquals(BulkResolveOutcome(3, 0), subject.handle(x, "user-2", command()))
    }
}
