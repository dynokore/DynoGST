package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.SignedBindings
import com.dynokore.gst.notification.adapters.delivery.BoundMessageChannels
import com.dynokore.gst.notification.adapters.delivery.ClosedTemplateVersions
import com.dynokore.gst.notification.adapters.persistence.JooqMessageDeliveryRecords
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.MessageDelivery.Companion.MESSAGE_DELIVERY
import com.dynokore.gst.notification.application.delivery.RefusingPasscodeOpener
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-29024 (INV-REQ-1162): a MessageRequested of the evidence-svc that carries the tenant-wide entity (no
 * `entityref` on the wire) is read by the library reader and handled by [MessageRequestedHandler] over the real
 * database; the same record from any other source is refused by the reader and writes no delivery record.
 */
class TenantWideMessageRequestedTest {
    private val json = EnvelopeJson()
    private val tenant = UUID.randomUUID()
    private val tenantWide = "00000000-0000-0000-0000-000000000000"

    private class RecordingChannel : MessageDelivery {
        override val channel = MessageChannel.EMAIL
        val sent = mutableListOf<OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            sent += message
            return DeliveryReceipt("d-${sent.size}")
        }

        override fun status(deliveryId: String) = DeliveryStatus(deliveryId, DeliveryState.SENT)
    }

    private val link =
        """{"template":"signed-link","recipients":["a@mail.test"],"channel":"EMAIL","linkReference":"link-ref-1"}"""

    /** The wire text of the tenant-wide record: the library writer leaves `entityref` out for the reserved entity. */
    private fun wireText(): String =
        json
            .write(
                Envelope(
                    id = EventId("ev-" + UUID.randomUUID()),
                    source = EVIDENCE_SOURCE,
                    type = MessageRequestedHandler.EVENT_TYPE,
                    subject = "tenant-wide",
                    time = Instant.parse("2026-11-28T10:00:00Z"),
                    tenant = TenantId(tenant),
                    entity = EntityRef.TENANT_WIDE,
                    docUuid = DocUuid(UUID.randomUUID()),
                    transactionDate = LocalDate.of(2026, 11, 28),
                    docSequence = 1,
                    instanceId = "evidence-svc-0",
                    correlation = CorrelationId("corr-1"),
                    causation = CausationId("cause-1"),
                    dataJson = link,
                ),
            ).also { it shouldNotContain "entityref" }

    private fun bound(block: (DSLContext, MessageRequestedHandler, RecordingChannel) -> Unit) =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                val dsl = DSL.using(c, SQLDialect.POSTGRES)
                val channel = RecordingChannel()
                val tenants =
                    object : TenantContext {
                        override fun current() = TenantId(tenant)

                        override fun currentActor(): String = SignedBindings.ACTOR

                        override fun <T> bind(
                            tenant: TenantId,
                            actor: String,
                            block: () -> T,
                        ): T = block()
                    }
                val handler =
                    MessageRequestedHandler(
                        JooqMessageDeliveryRecords(dsl, tenants),
                        BoundMessageChannels(listOf(channel)),
                        ClosedTemplateVersions(),
                        Clock.systemUTC(),
                        ObjectMapper(),
                        RefusingPasscodeOpener,
                    )
                block(dsl, handler, channel)
            } finally {
                c.rollback()
            }
        }

    private fun rows(dsl: DSLContext): List<List<String?>> =
        dsl
            .selectFrom(MESSAGE_DELIVERY)
            .fetch()
            .map { listOf(it.tenantId.toString(), it.entityRef, it.state, it.recipientRef) }

    @Test
    fun `INV-AC-29024 a MessageRequested of the evidence-svc without entityref is read as tenant-wide and delivered`() =
        bound { dsl, handler, channel ->
            val event = json.read(wireText())
            event.source shouldBe EVIDENCE_SOURCE
            event.entity.isTenantWide shouldBe true
            handler.handle(event) shouldBe HandleOutcome.APPLIED
            rows(dsl) shouldBe listOf(listOf(tenant.toString(), tenantWide, "SENT", "recipient-1"))
            channel.sent.size shouldBe 1
        }

    @Test
    fun `INV-AC-29024 the same record from the access-svc is refused by the reader and writes no delivery record`() =
        bound { dsl, _, channel ->
            val refused = wireText().replace(EVIDENCE_SOURCE, "/gst/access-svc")
            shouldThrow<ContractViolation> { json.read(refused) }
            rows(dsl) shouldBe emptyList()
            channel.sent.size shouldBe 0
        }

    @Test
    fun `INV-AC-29024 a look-alike source of the evidence-svc is refused and writes no delivery record`() =
        bound { dsl, _, channel ->
            listOf("/gst/evidence-svcx", "/gst/Evidence-svc", "/gst/evidence-svc/").forEach { source ->
                shouldThrow<ContractViolation> { json.read(wireText().replace(EVIDENCE_SOURCE, source)) }
            }
            rows(dsl) shouldBe emptyList()
            channel.sent.size shouldBe 0
        }

    private companion object {
        const val EVIDENCE_SOURCE = "/gst/evidence-svc"
    }
}
