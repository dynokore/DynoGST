package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.CanonicalJson
import com.dynokore.gst.evidence.adapters.events.PayloadRejectedHandler
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.chain.AppliedStore
import com.dynokore.gst.evidence.application.chain.LocatorStore
import com.dynokore.gst.evidence.application.chain.PendingRecord
import com.dynokore.gst.evidence.application.chain.PendingStore
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-27154: the handler of `PayloadRejected` appends the `decisions` member as a DECISION_TRACE record. With no
 * locator stored the appender keeps the record pending, so the pending store is the recording appender: every
 * `appendBound` call leaves exactly one [PendingRecord] carrying the kind, the version and the content.
 */
class PayloadRejectedHandlerTest {
    private val pending = mutableListOf<PendingRecord>()
    private val tenant = TenantId(UUID.randomUUID())
    private val doc = UUID.randomUUID()
    private val decisions = """[{"outcome":"rejected","code":"PAY-002"}]"""

    private val handler: PayloadRejectedHandler by lazy {
        val ledger =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EvidenceLedger::class.java)) { _, _, _ ->
                error("the ledger is not reached while no locator is stored")
            } as EvidenceLedger
        val locators =
            object : LocatorStore {
                override fun find(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                ): DocumentLocator? = null

                override fun insert(
                    tenant: TenantId,
                    eventId: String,
                    locator: DocumentLocator,
                    recordedAt: Instant,
                ) = error("not used")

                override fun lock(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                ) = Unit
            }
        val store =
            object : PendingStore {
                override fun insert(
                    tenant: TenantId,
                    record: PendingRecord,
                ) = pending.add(record)

                override fun unapplied(
                    tenant: TenantId,
                    docUuid: UUID,
                    version: Int,
                ) = emptyList<PendingRecord>()
            }
        val applied =
            object : AppliedStore {
                override fun isApplied(
                    tenant: TenantId,
                    eventId: String,
                ) = false

                override fun insert(
                    tenant: TenantId,
                    record: AppliedRecord,
                ) = Unit
            }
        PayloadRejectedHandler(RecordAppender(RecordKind.entries.associateWith { ledger }, locators, store, applied))
    }

    private fun envelope(
        data: String,
        id: String = "ev-1",
    ) = Envelope(
        id = EventId(id),
        source = "/gst/payload-svc",
        type = "com.dynokore.gst.payload.PayloadRejected.v1",
        subject = doc.toString(),
        time = Instant.parse("2026-01-15T00:00:00Z"),
        tenant = tenant,
        entity = EventEntityRef("ENT-3F2A9C"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 1, 15),
        docSequence = null,
        instanceId = "payload-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun data(
        docUuid: String = """"$doc"""",
        version: String = "1",
        decisions: String? = this.decisions,
    ) = buildString {
        append("""{"reason":"x","docUuid":$docUuid,"version":$version""")
        if (decisions != null) append(""","decisions":$decisions""")
        append("}")
    }

    private fun refused(data: String) {
        shouldThrow<ContractViolation> { handler.handle(envelope(data)) }
        pending shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27154 an event with decisions appends one DECISION_TRACE record with the member as sent`() {
        handler.handle(envelope(data())) shouldBe HandleOutcome.APPLIED
        val record = pending.single()
        record.kind shouldBe RecordKind.DECISION_TRACE
        record.docUuid shouldBe doc
        record.version shouldBe 1
        record.eventId shouldBe "ev-1"
        val expected = JsonMapper.builder().build().readTree("""{"decisions":$decisions}""")
        record.eventJson.toByteArray(Charsets.UTF_8) shouldBe CanonicalJson.bytes(expected)
    }

    @Test
    fun `INV-AC-27154 an event without decisions is applied and appends nothing`() {
        handler.handle(envelope(data(decisions = null))) shouldBe HandleOutcome.APPLIED
        pending shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27154 an empty decisions array is a contract violation and appends nothing`() {
        refused(data(decisions = "[]"))
    }

    @Test
    fun `INV-AC-27154 a decisions member that is not an array is a contract violation`() {
        refused(data(decisions = """{"code":"PAY-002"}"""))
        refused(data(decisions = """"PAY-002""""))
        refused(data(decisions = "null"))
    }

    @Test
    fun `INV-AC-27154 a payload without docUuid is a contract violation`() {
        refused("""{"version":1,"decisions":$decisions}""")
        refused(data(docUuid = """"not-a-uuid""""))
        refused("not json")
    }

    @Test
    fun `INV-AC-27154 a version of 0 is a contract violation`() {
        refused(data(version = "0"))
        refused(data(version = "-1"))
        refused(data(version = "1.5"))
        refused(data(version = """"1""""))
    }

    @Test
    fun `INV-AC-27154 a PayloadRejected event of another version is a separate record`() {
        handler.handle(envelope(data(), "ev-1")) shouldBe HandleOutcome.APPLIED
        handler.handle(envelope(data(version = "2"), "ev-2")) shouldBe HandleOutcome.APPLIED
        pending.map { it.docUuid to it.version } shouldBe listOf(doc to 1, doc to 2)
        pending.map { it.eventId } shouldBe listOf("ev-1", "ev-2")
    }
}
