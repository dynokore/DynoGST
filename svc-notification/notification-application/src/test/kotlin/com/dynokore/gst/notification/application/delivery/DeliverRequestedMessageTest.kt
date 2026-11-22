package com.dynokore.gst.notification.application.delivery

import com.dynokore.gst.notification.application.alerting.ContractViolation
import com.dynokore.gst.notification.domain.delivery.DeliveryChannel
import com.dynokore.gst.notification.domain.delivery.DeliveryRecordState
import com.dynokore.gst.notification.domain.delivery.MessageDeliveryRecord
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.serviceruntime.binding.TemplateValuesContent
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class DeliverRequestedMessageTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val address = "alice@example.com"
    private val address2 = "bob@example.com"
    private val mobile = "+6591234567"
    private val passcode = "SEALED-PASSCODE-9f3a"

    private class FakeRecords : MessageDeliveryRecords {
        val rows = linkedMapOf<Pair<String, Int>, MessageDeliveryRecord>()

        override fun insertIfAbsent(record: MessageDeliveryRecord): Boolean {
            val key = record.eventId to record.position
            if (rows.containsKey(key)) return false
            rows[key] = record
            return true
        }

        override fun update(record: MessageDeliveryRecord) {
            rows[record.eventId to record.position] = record
        }
    }

    private class FakeChannel(
        override val channel: MessageChannel,
        private val failWith: RuntimeException? = null,
    ) : MessageDelivery {
        val sent = mutableListOf<OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            sent += message
            failWith?.let { throw it }
            return DeliveryReceipt("d-${sent.size}")
        }

        override fun status(deliveryId: String) = DeliveryStatus(deliveryId, DeliveryState.SENT)
    }

    private class Fixture(
        val records: FakeRecords,
        val email: FakeChannel?,
        val sms: FakeChannel?,
        val useCase: DeliverRequestedMessage,
    )

    private fun fixture(
        email: FakeChannel? = FakeChannel(MessageChannel.EMAIL),
        sms: FakeChannel? = FakeChannel(MessageChannel.SMS),
        known: Set<String> = setOf("signed-link", "signed-link-passcode", "retention-notice"),
    ): Fixture {
        val records = FakeRecords()
        val channels =
            object : MessageChannels {
                override fun forChannel(channel: DeliveryChannel) = if (channel == DeliveryChannel.EMAIL) email else sms
            }
        val templates =
            object : TemplateVersions {
                override fun versionOf(templateId: String) = if (templateId in known) "1" else null
            }
        return Fixture(
            records,
            email,
            sms,
            DeliverRequestedMessage(records, channels, templates, clock, PasscodeOpener { sealed, _ -> sealed }),
        )
    }

    private fun Fixture.twice(payload: Map<String, Any?>) {
        repeat(2) { useCase.handle(tenant, "ent-1", "evt-1", payload) }
    }

    private fun linkPayload(vararg to: String) =
        mapOf(
            "template" to "signed-link",
            "channel" to "EMAIL",
            "recipients" to to.toList(),
            "linkReference" to "link-ref-1",
        )

    private fun record(
        position: Int,
        state: DeliveryRecordState,
        channel: DeliveryChannel,
        template: String,
        version: String?,
    ) = MessageDeliveryRecord(
        messageId = MessageDeliveryRecord.messageId("evt-1", position),
        eventId = "evt-1",
        position = position,
        entityRef = "ent-1",
        channel = channel,
        template = template,
        templateVersion = version,
        recipientRef = "recipient-${position + 1}",
        state = state,
    )

    private fun sent(
        position: Int,
        channel: DeliveryChannel,
        template: String,
        deliveryId: String,
    ) = record(position, DeliveryRecordState.QUEUED, channel, template, "1")
        .copy(state = DeliveryRecordState.SENT, deliveryId = deliveryId, sentAt = now)

    private fun failed(
        position: Int,
        template: String,
        version: String?,
        reason: String,
    ) = record(position, DeliveryRecordState.QUEUED, DeliveryChannel.EMAIL, template, version)
        .copy(state = DeliveryRecordState.FAILED, failureReason = reason)

    @Test
    @DisplayName("INV-AC-26362 an e-mail link message gives one SENT record and one send over two runs")
    fun emailLink() {
        val f = fixture()
        f.twice(linkPayload(address))
        f.records.rows.values
            .toList() shouldBe
            listOf(sent(0, DeliveryChannel.EMAIL, "signed-link", "d-1"))
        f.email!!.sent.size shouldBe 1
        f.email.sent[0].content shouldBe LinkContent("link-ref-1")
        f.email.sent[0].to shouldBe address
    }

    @Test
    @DisplayName("INV-AC-26362 an SMS passcode message gives one SENT record and one send over two runs")
    fun smsPasscode() {
        val f = fixture()
        f.twice(
            mapOf(
                "template" to "signed-link-passcode",
                "channel" to "SMS",
                "recipients" to listOf(mobile),
                "sealedPasscode" to passcode,
            ),
        )
        f.records.rows.values
            .toList() shouldBe
            listOf(sent(0, DeliveryChannel.SMS, "signed-link-passcode", "d-1"))
        f.sms!!.sent.size shouldBe 1
        f.sms.sent[0].content shouldBe PasscodeContent(passcode)
    }

    @Test
    @DisplayName("INV-AC-26362 two recipients give two records and two sends over two runs")
    fun twoRecipients() {
        val f = fixture()
        f.twice(linkPayload(address, address2))
        f.records.rows.values
            .toList() shouldBe
            listOf(
                sent(0, DeliveryChannel.EMAIL, "signed-link", "d-1"),
                sent(1, DeliveryChannel.EMAIL, "signed-link", "d-2"),
            )
        f.email!!.sent.map { it.to } shouldBe listOf(address, address2)
    }

    @Test
    @DisplayName("INV-AC-26362 a channel with no bound MessageDelivery gives FAILED CHANNEL_NOT_BOUND and no send")
    fun channelNotBound() {
        val f = fixture(email = null)
        f.twice(linkPayload(address))
        f.records.rows.values
            .toList() shouldBe
            listOf(
                failed(0, "signed-link", "1", "CHANNEL_NOT_BOUND"),
            )
        f.sms!!.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26362 an unknown template gives FAILED TEMPLATE_UNKNOWN and no send")
    fun templateUnknown() {
        val f = fixture()
        f.twice(linkPayload(address) + ("template" to "nope"))
        f.records.rows.values
            .toList() shouldBe
            listOf(failed(0, "nope", null, "TEMPLATE_UNKNOWN"))
        f.email!!.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26362 a port whose send throws gives FAILED SEND_REFUSED, one call, no exception text kept")
    fun sendRefused() {
        val f = fixture(email = FakeChannel(MessageChannel.EMAIL, IllegalStateException("smtp refused $address")))
        f.twice(linkPayload(address))
        f.records.rows.values
            .toList() shouldBe
            listOf(failed(0, "signed-link", "1", "SEND_REFUSED"))
        f.email!!.sent.size shouldBe 1
    }

    @Test
    @DisplayName("INV-AC-26362 an SMS payload without sealedPasscode or carrying linkReference is a contract violation")
    fun smsViolations() {
        val f = fixture()
        val base = mapOf("template" to "signed-link-passcode", "channel" to "SMS", "recipients" to listOf(mobile))
        val withLink = base + ("linkReference" to "l")
        for (p in listOf(base, withLink, withLink + ("sealedPasscode" to passcode))) {
            shouldThrow<ContractViolation> { f.useCase.handle(tenant, "ent-1", "evt-1", p) }
        }
        f.records.rows.size shouldBe 0
        f.sms!!.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26362 empty, blank, oversize-passcode and contentless payloads are contract violations")
    fun otherViolations() {
        val f = fixture()
        val bad =
            listOf(
                linkPayload(),
                linkPayload(" "),
                linkPayload(address) - "linkReference",
                linkPayload(address, address2) - "linkReference" + ("sealedPasscode" to passcode),
                linkPayload(address) + ("channel" to "PIGEON"),
            )
        for (p in bad) shouldThrow<ContractViolation> { f.useCase.handle(tenant, "ent-1", "evt-1", p) }
        f.records.rows.size shouldBe 0
        f.email!!.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-26362 no log line, record or exception message holds an address, mobile or passcode")
    fun noSensitiveValueLeaks() {
        val lines = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(r: LogRecord) {
                    lines += "${r.message} ${r.parameters?.joinToString()} ${r.thrown}"
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val root = Logger.getLogger("")
        root.addHandler(handler)
        val texts = mutableListOf<String>()
        try {
            val boom = IllegalStateException("gateway refused $mobile $passcode")
            val f = fixture(sms = FakeChannel(MessageChannel.SMS, boom))
            f.twice(
                mapOf(
                    "template" to "signed-link-passcode",
                    "channel" to "SMS",
                    "recipients" to listOf(mobile),
                    "sealedPasscode" to passcode,
                ),
            )
            val g = fixture(email = FakeChannel(MessageChannel.EMAIL, IllegalStateException("refused $address")))
            g.twice(linkPayload(address))
            for (x in listOf(f, g)) {
                x.records.rows.values
                    .forEach { texts += it.toString() }
            }
            val sms = mapOf("template" to "signed-link-passcode", "channel" to "SMS", "recipients" to listOf(mobile))
            val uc = fixture().useCase
            val p = sms + ("linkReference" to passcode)
            texts += shouldThrow<ContractViolation> { uc.handle(tenant, "ent-1", "evt-2", p) }.message.orEmpty()
            val blank = linkPayload(address, " ")
            texts += shouldThrow<ContractViolation> { uc.handle(tenant, "ent-1", "evt-3", blank) }.message.orEmpty()
        } finally {
            root.removeHandler(handler)
        }
        for (t in texts + lines) {
            for (secret in listOf(address, address2, mobile, passcode)) (secret in t) shouldBe false
        }
    }

    private val retentionValues =
        mapOf("periodStart" to "2026-01-01", "periodEnd" to "2026-12-31", "noticeWindowEndsAt" to "2027-01-31")

    private fun retentionPayload(vararg to: String = arrayOf(address)) =
        mapOf(
            "template" to "retention-notice",
            "channel" to "EMAIL",
            "recipients" to to.toList(),
            "templateParameters" to retentionValues,
        )

    @Test
    @DisplayName("INV-AC-27030 a conforming retention-notice gives one TemplateValuesContent send, one SENT record")
    fun retentionConforming() {
        val f = fixture()
        f.twice(retentionPayload())
        f.records.rows.values
            .toList() shouldBe
            listOf(sent(0, DeliveryChannel.EMAIL, "retention-notice", "d-1"))
        f.email!!.sent.size shouldBe 1
        val m = f.email.sent[0]
        m.templateId shouldBe "retention-notice"
        m.templateVersion shouldBe "1"
        m.channel shouldBe MessageChannel.EMAIL
        m.to shouldBe address
        m.content shouldBe TemplateValuesContent(retentionValues)
    }

    private fun valueDeviations(): Map<String, Map<String, Any?>> {
        val ok = retentionPayload()
        return mapOf(
            "missing key" to ok + ("templateParameters" to retentionValues - "periodEnd"),
            "extra key" to ok + ("templateParameters" to retentionValues + ("extra" to "x")),
            "other case" to
                ok + ("templateParameters" to (retentionValues - "periodStart") + ("periodstart" to "2026-01-01")),
            "number value" to ok + ("templateParameters" to retentionValues + ("periodEnd" to 20261231)),
            "null value" to ok + ("templateParameters" to retentionValues + ("periodEnd" to null)),
            "blank value" to ok + ("templateParameters" to retentionValues + ("periodEnd" to " ")),
            "empty object" to ok + ("templateParameters" to emptyMap<String, String>()),
            "not an object" to ok + ("templateParameters" to "x"),
        )
    }

    private fun shapeDeviations(): Map<String, Map<String, Any?>> {
        val ok = retentionPayload()
        val onlyPasscode = ok - "templateParameters" + ("sealedPasscode" to passcode)
        return mapOf(
            "sms" to ok + ("channel" to "SMS"),
            "two recipients" to retentionPayload(address, address2),
            "with link" to ok + ("linkReference" to "l"),
            "with passcode" to ok + ("sealedPasscode" to passcode),
            "signed-link with parameters" to ok + ("template" to "signed-link"),
            "signed-link-passcode with parameters" to ok + ("template" to "signed-link-passcode"),
            "unknown template with parameters" to ok + ("template" to "Retention-Notice"),
            "retention with link only" to linkPayload(address) + ("template" to "retention-notice"),
            "retention with passcode only" to onlyPasscode,
            "retention with nothing" to ok - "templateParameters",
        )
    }

    @Test
    @DisplayName("INV-AC-27030 every deviating retention-notice payload is a violation with no record and no send")
    fun retentionDeviations() {
        val f = fixture()
        for ((name, p) in valueDeviations() + shapeDeviations()) {
            try {
                f.useCase.handle(tenant, "ent-1", "evt-1", p)
                throw AssertionError("not refused: $name")
            } catch (_: ContractViolation) {
                // expected
            }
        }
        f.records.rows.size shouldBe 0
        f.email!!.sent.size shouldBe 0
        f.sms!!.sent.size shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-27030 no log line or exception message holds a template value")
    fun retentionValuesNeverLeak() {
        val lines = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(r: LogRecord) {
                    lines += "${r.message} ${r.parameters?.joinToString()} ${r.thrown}"
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val root = Logger.getLogger("")
        root.addHandler(handler)
        val texts = mutableListOf<String>()
        try {
            val boom = IllegalStateException("refused $retentionValues")
            val f = fixture(email = FakeChannel(MessageChannel.EMAIL, boom))
            f.twice(retentionPayload())
            f.records.rows.values
                .forEach { texts += it.toString() }
            val uc = fixture().useCase
            val ok = retentionPayload()
            val deviations =
                listOf(
                    ok + ("templateParameters" to retentionValues + ("extra" to "x")),
                    ok + ("templateParameters" to retentionValues + ("periodEnd" to 5)),
                    ok + ("channel" to "SMS"),
                    ok + ("linkReference" to "l"),
                    ok + ("template" to "signed-link"),
                    retentionPayload(address, address2),
                )
            for (p in deviations) {
                texts += shouldThrow<ContractViolation> { uc.handle(tenant, "ent-1", "evt-9", p) }.message.orEmpty()
            }
        } finally {
            root.removeHandler(handler)
        }
        for (t in texts + lines) {
            for (v in retentionValues.values) (v in t) shouldBe false
        }
    }
}
