package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SendNetworkDocumentTest {
    private data class Call(
        val doc: DocRef,
        val recipient: String,
        val adapter: AdapterId,
    )

    private class Sender : NetworkSender {
        val calls = mutableListOf<Call>()

        override fun send(
            doc: DocRef,
            payload: ByteArray,
            recipient: String,
            adapter: AdapterId,
        ): TransportOutcome {
            calls += Call(doc, recipient, adapter)
            return TransportOutcome.Accepted("ack-${calls.size}")
        }
    }

    private class Log : AttemptLog {
        data class Row(
            val id: UUID,
            val entity: EntityRef,
            val doc: DocRef,
            val adapter: AdapterId,
            var outcome: TransportOutcome? = null,
        )

        val rows = mutableListOf<Row>()

        override fun adapterInFlight(doc: DocRef): AdapterId? = rows.firstOrNull { it.doc == doc }?.adapter

        override fun entityOf(docUuid: UUID): EntityRef? = rows.firstOrNull { it.doc.docUuid == docUuid }?.entity

        override fun acceptedAt(doc: DocRef): Instant? = null

        override fun settledOutcome(doc: DocRef): TransportOutcome? =
            rows
                .firstOrNull { it.doc == doc && it.outcome != null && it.outcome != TransportOutcome.TransientFailure }
                ?.outcome

        override fun begin(
            entity: EntityRef,
            doc: DocRef,
            adapter: AdapterId,
            at: Instant,
        ): UUID = UUID.randomUUID().also { rows += Row(it, entity, doc, adapter) }

        override fun complete(
            attempt: UUID,
            outcome: TransportOutcome,
            at: Instant,
        ) {
            rows.first { it.id == attempt }.outcome = outcome
        }

        override fun counts(
            adapter: AdapterId,
            since: Instant,
        ): AttemptCounts = AttemptCounts(0, 0)
    }

    private class Connections : EntityAdapterAssignments {
        val map = mutableMapOf<EntityRef, AdapterId>()

        override fun adapterFor(entity: EntityRef): AdapterId? = map[entity]

        override fun assign(
            entity: EntityRef,
            adapter: AdapterId,
        ) {
            map[entity] = adapter
        }
    }

    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val e3 = EntityRef("E3")
    private val a1 = AdapterId("A1")
    private val u1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val u9 = UUID.fromString("00000000-0000-0000-0000-000000000009")
    private val sender = Sender()
    private val log = Log()
    private val connections = Connections().also { it.assign(e1, a1) }
    private val useCase =
        SendNetworkDocument(sender, log, connections, Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC))
    private val payload = "<Invoice/>".toByteArray()

    init {
        // U9 is recorded under E2 (the Doc UUID register)
        log.rows += Log.Row(UUID.randomUUID(), e2, DocRef(u9, 1), AdapterId("A2"), TransportOutcome.Accepted("old"))
    }

    @Test
    fun `INV-AC-27501 first send goes once to the recipient through the adapter of the connection`() {
        val outcome = useCase.send(e1, DocRef(u1, 1), payload, "R1")

        outcome shouldBe TransportOutcome.Accepted("ack-1")
        sender.calls shouldBe listOf(Call(DocRef(u1, 1), "R1", a1))
    }

    @Test
    fun `INV-AC-27501 repeat with the same key returns the original outcome and sends nothing`() {
        val first = useCase.send(e1, DocRef(u1, 1), payload, "R1")
        val second = useCase.send(e1, DocRef(u1, 1), payload, "R1")

        second shouldBe first
        sender.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-27501 Doc UUID recorded under another entity is refused and nothing is sent`() {
        shouldThrow<DocUuidRecordedUnderOtherEntity> { useCase.send(e1, DocRef(u9, 1), payload, "R1") }

        sender.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27501 same Doc UUID with version 2 is a new send`() {
        useCase.send(e1, DocRef(u1, 1), payload, "R1")
        useCase.send(e1, DocRef(u1, 2), payload, "R1")

        sender.calls.map { it.doc } shouldBe listOf(DocRef(u1, 1), DocRef(u1, 2))
    }

    @Test
    fun `INV-AC-27501 entity without connection record is refused and nothing is sent`() {
        shouldThrow<NoApConnectionRecord> { useCase.send(e3, DocRef(u1, 1), payload, "R1") }

        sender.calls shouldBe emptyList()
        log.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-27501 another tenant Doc UUID is not found and its rows are untouched`() {
        val before = log.rows.map { it.copy() }

        shouldThrow<DocUuidRecordedUnderOtherEntity> { useCase.send(e1, DocRef(u9, 1), payload, "R1") }

        log.rows shouldBe before
        sender.calls shouldBe emptyList()
    }
}
