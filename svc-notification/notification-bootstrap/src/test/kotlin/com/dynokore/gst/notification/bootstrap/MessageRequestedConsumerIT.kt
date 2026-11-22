package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.delivery.BoundMessageChannels
import com.dynokore.gst.notification.adapters.delivery.ClosedTemplateVersions
import com.dynokore.gst.notification.adapters.events.MessageRequestedHandler
import com.dynokore.gst.notification.adapters.persistence.JooqMessageDeliveryRecords
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.MessageDelivery.Companion.MESSAGE_DELIVERY
import com.dynokore.gst.notification.application.delivery.RefusingPasscodeOpener
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-26363 through the real inbox consumer (`JdbcInboxConsumer`), the handler, the use case and the jOOQ store of
 * the TPL-18 context over the Flyway-built schema (P109). Only the event-log read and the channel are fakes.
 */
class MessageRequestedConsumerIT {
    private val actor = "svc:notification-svc"
    private val json = EnvelopeJson()
    private var sequence = 0L

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    /** A recording channel: it keeps what it was asked to send, in memory only. */
    private class Fake(
        override val channel: MessageChannel,
    ) : MessageDelivery {
        val sent = mutableListOf<OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            sent += message
            return DeliveryReceipt("d-${channel.name}-${sent.size}")
        }

        override fun status(deliveryId: String) = DeliveryStatus(deliveryId, DeliveryState.SENT)
    }

    private class Boundary(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val email = Fake(MessageChannel.EMAIL)
        val sms = Fake(MessageChannel.SMS)

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        val consumerName = MessageRequestedConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    MessageRequestedConsumerConfiguration.TOPIC,
                    MessageRequestedConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                MessageRequestedHandler(
                    JooqMessageDeliveryRecords(dsl, context.getBean(TenantContext::class.java)),
                    BoundMessageChannels(listOf(email, sms)),
                    ClosedTemplateVersions(),
                    Clock.systemUTC(),
                    ObjectMapper(),
                    RefusingPasscodeOpener,
                ),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        NotificationContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    // message_id is derived from the event id and is the primary key of a shared database: one id per test
    private fun eventId() = "ev-" + UUID.randomUUID()

    private fun envelope(
        tenant: TenantId,
        eventId: String,
        data: String,
        type: String = MessageRequestedHandler.EVENT_TYPE,
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/access-svc",
                type = type,
                subject = "E-1",
                time = Instant.parse("2026-11-21T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "access-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
            ),
        )

    private val link =
        """{"template":"signed-link","recipients":["a@mail.test","b@mail.test"],"channel":"EMAIL",""" +
            """"linkReference":"link-ref-1"}"""

    /** Every row of the table as text, so a column of any type is scanned. */
    private fun Boundary.rowTexts(tenant: TenantId): List<String> =
        tx(tenant) { dsl.selectFrom(MESSAGE_DELIVERY).fetch().map { r -> r.intoArray().joinToString("|") } }

    private fun Boundary.states(tenant: TenantId): List<List<String?>> =
        tx(tenant) {
            dsl.selectFrom(MESSAGE_DELIVERY).orderBy(MESSAGE_DELIVERY.POSITION).fetch().map {
                listOf(
                    it.position.toString(),
                    it.tenantId.toString(),
                    it.state,
                    it.recipientRef,
                    it.templateVersion,
                    it.deliveryId,
                )
            }
        }

    private fun Boundary.deadLetters(tenant: TenantId): List<String> =
        tx(tenant) {
            dsl
                .select(EVENT_DEAD_LETTER.REASON)
                .from(EVENT_DEAD_LETTER)
                .where(EVENT_DEAD_LETTER.CONSUMER.eq(consumerName))
                .fetch(EVENT_DEAD_LETTER.REASON)
                .filterNotNull()
        }

    @Test
    @DisplayName("INV-AC-26363 the envelope processed twice leaves one SENT row per recipient and one send each")
    fun twiceOneRowPerRecipient(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val line = envelope(tenant, eventId(), link)
        log.add(line)
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        log.add(line)
        consumer.pollOnce().applied shouldBe 0
        states(tenant) shouldBe
            listOf(
                listOf("0", tenant.value.toString(), "SENT", "recipient-1", "1", "d-EMAIL-1"),
                listOf("1", tenant.value.toString(), "SENT", "recipient-2", "1", "d-EMAIL-2"),
            )
        email.sent.map { it.to } shouldContainExactlyInAnyOrder listOf("a@mail.test", "b@mail.test")
        sms.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26363 a connection bound to another tenant reads zero rows of the table")
    fun otherTenantReadsNothing(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(envelope(tenant, eventId(), link))
        consumer.pollOnce().applied shouldBe 1
        rowTexts(tenant).size shouldBe 2
        rowTexts(TenantId(UUID.randomUUID())) shouldBe emptyList()
    }

    @Test
    @DisplayName(
        "INV-AC-26363 a wrong event type goes to the dead letter and leaves the table and the channel untouched",
    )
    fun wrongTypeDeadLetters(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(envelope(tenant, eventId(), link, type = "com.dynokore.gst.access.MessageRequested.v2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        deadLetters(tenant).single().startsWith("unexpected event type") shouldBe true
        rowTexts(tenant) shouldBe emptyList()
        email.sent.size + sms.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26363 a payload that is not a JSON object goes to the dead letter and writes nothing")
    fun nonObjectDeadLetters(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(envelope(tenant, eventId(), "[1,2]"))
        consumer.pollOnce().deadLettered shouldBe 1
        deadLetters(tenant).single().startsWith("payload is not a JSON object") shouldBe true
        rowTexts(tenant) shouldBe emptyList()
        email.sent.size + sms.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26363 a payload the use case refuses goes to the dead letter without the value")
    fun refusedPayloadDeadLetters(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(envelope(tenant, eventId(), """{"template":"signed-link","recipients":[],"channel":"EMAIL"}"""))
        consumer.pollOnce().deadLettered shouldBe 1
        rowTexts(tenant) shouldBe emptyList()
    }

    @Test
    @DisplayName("INV-AC-26363 no column of any row holds the sealedPasscode or an address")
    fun noPasscodeNoAddressInRows(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val sealed = "SEALED-PASSCODE-9f3a7c21"
        log.add(
            envelope(
                tenant,
                eventId(),
                """{"template":"signed-link-passcode","recipients":["+6591234567"],"channel":"SMS",""" +
                    """"sealedPasscode":"$sealed"}""",
            ),
        )
        consumer.pollOnce().applied shouldBe 1
        sms.sent.size shouldBe 0
        val rows = rowTexts(tenant)
        rows.size shouldBe 1
        rows.single() shouldNotContain sealed
        rows.single() shouldNotContain "+6591234567"
        states(tenant).single()[2] shouldBe "FAILED"
        tx(tenant) {
            dsl
                .selectFrom(MESSAGE_DELIVERY)
                .fetch()
                .single()
                .failureReason
        } shouldBe "PASSCODE_UNOPENABLE"
    }

    @Test
    @DisplayName("INV-AC-26363 with no channel bound the record is FAILED CHANNEL_NOT_BOUND and the event is applied")
    fun unboundChannelFailsClosed(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val unbound =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    MessageRequestedConsumerConfiguration.TOPIC,
                    MessageRequestedConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                MessageRequestedHandler(
                    JooqMessageDeliveryRecords(dsl, context.getBean(TenantContext::class.java)),
                    BoundMessageChannels(emptyList()),
                    ClosedTemplateVersions(),
                    Clock.systemUTC(),
                    ObjectMapper(),
                    RefusingPasscodeOpener,
                ),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
        log.add(envelope(tenant, eventId(), link))
        unbound.pollOnce().applied shouldBe 1
        tx(tenant) {
            dsl
                .selectFrom(
                    MESSAGE_DELIVERY,
                ).orderBy(MESSAGE_DELIVERY.POSITION)
                .fetch()
                .map { listOf(it.state, it.failureReason) }
        } shouldBe List(2) { listOf("FAILED", "CHANNEL_NOT_BOUND") }
    }
}
