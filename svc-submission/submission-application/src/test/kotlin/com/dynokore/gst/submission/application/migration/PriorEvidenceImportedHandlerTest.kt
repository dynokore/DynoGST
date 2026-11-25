package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The PriorSubmissionEvidenceImported consumer with an in-memory port and a fake reader (INV-AC-28453). */
class PriorEvidenceImportedHandlerTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000032"))
    private val date = LocalDate.of(2026, 2, 27)

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = t1

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    /** Mirrors the port contract: insert once per key, repeat is false, another acknowledgement throws. */
    private class Memory : ImportedPriorEvidenceStore {
        val rows = mutableMapOf<List<Any>, Pair<ImportedPriorRecord, String>>()

        override fun record(
            tenant: TenantId,
            record: ImportedPriorRecord,
            priorSolution: String,
        ): Boolean {
            val key = listOf(tenant, record.entityRef, record.documentNumber, record.transactionDate)
            val old = rows[key]
            if (old != null) {
                if (old.first.acknowledgementRef != record.acknowledgementRef) throw ContractViolation("conflict")
                return false
            }
            rows[key] = record to priorSolution
            return true
        }

        override fun listFor(
            tenant: TenantId,
            entityRef: String,
        ): List<ImportedPriorRecord> = rows.filterKeys { it[0] == tenant && it[1] == entityRef }.values.map { it.first }
    }

    private val store = Memory()
    private var next: () -> PriorEvidenceImportedData = { error("unset") }
    private val handler = PriorEvidenceImportedHandler(tenants, { next() }, store)

    private fun deliver(
        entity: String = "E-1",
        doc: String = "INV-100",
        ack: String = "ACK-1",
        on: LocalDate = date,
    ): HandleOutcome {
        next = { PriorEvidenceImportedData(entity, doc, on, ack, "P1") }
        return handler.handle(envelope())
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/evidence-svc",
            type = "com.dynokore.gst.evidence.PriorSubmissionEvidenceImported.v1",
            subject = "E-1",
            time = Instant.parse("2026-11-25T08:00:00Z"),
            tenant = t1,
            entity = EntityRef("E-1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "evidence-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-28453 the six events store one record per document and isolate entity and tenant`() {
        val first = ImportedPriorRecord("E-1", "INV-100", date, "ACK-1")
        deliver() shouldBe HandleOutcome.APPLIED
        deliver() shouldBe HandleOutcome.IGNORED
        shouldThrow<ContractViolation> { deliver(ack = "ACK-2") }
        store.listFor(t1, "E-1") shouldBe listOf(first)

        deliver(entity = "E-2") shouldBe HandleOutcome.APPLIED
        store.listFor(t1, "E-2") shouldBe listOf(ImportedPriorRecord("E-2", "INV-100", date, "ACK-1"))
        store.listFor(t1, "E-1") shouldBe listOf(first)
        store.listFor(t2, "E-1") shouldBe emptyList()
        store.rows.size shouldBe 2
    }

    @Test
    fun `INV-AC-28453 the port receives the priorSolution of the event`() {
        val event = PriorEvidenceImportedData("E-1", "INV-100", date, "ACK-1", "Legacy Books")
        next = { event }
        handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        store.rows.values
            .single()
            .second shouldBe event.priorSolution
    }

    @Test
    fun `INV-AC-28453 another transaction date is another document`() {
        deliver() shouldBe HandleOutcome.APPLIED
        deliver(on = date.plusDays(1), ack = "ACK-2") shouldBe HandleOutcome.APPLIED
        store.listFor(t1, "E-1").size shouldBe 2
    }

    @Test
    fun `INV-AC-28453 a malformed event is a ContractViolation naming the member and stores nothing`() {
        val failing =
            PriorEvidenceImportedHandler(
                tenants,
                { throw ContractViolation("PriorSubmissionEvidenceImported member acknowledgementRef is missing") },
                store,
            )
        val e = shouldThrow<ContractViolation> { failing.handle(envelope()) }
        e.message.orEmpty() shouldContain "acknowledgementRef"
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-28453 a blank member is a ContractViolation that does not echo the value`() {
        next = { PriorEvidenceImportedData(" ", "INV-100", date, "ACK-1", "P1") }
        val e = shouldThrow<ContractViolation> { handler.handle(envelope()) }
        e.message.orEmpty() shouldContain "entityRef"
        e.message.orEmpty() shouldNotContain "INV-100"
        next = { PriorEvidenceImportedData("E-1", "INV-100", date, "ACK-1", " ") }
        shouldThrow<ContractViolation> { handler.handle(envelope()) }
        store.rows.size shouldBe 0
    }
}
