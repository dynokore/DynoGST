package com.dynokore.gst.notification.application.delivery

import com.dynokore.gst.notification.domain.delivery.DeliveryChannel
import com.dynokore.gst.notification.domain.delivery.DeliveryRecordState
import com.dynokore.gst.notification.domain.delivery.MessageDeliveryRecord
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.DeliveryState
import com.dynokore.platform.serviceruntime.binding.DeliveryStatus
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
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

class DeliverPasscodeMessageTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC)
    private val recipient = "alice@example.com"
    private val clear = "123456"
    private val sealed = "v1.0123456789abcdef.SEALEDCIPHERTEXT"

    private class Records : MessageDeliveryRecords {
        val rows = linkedMapOf<Pair<String, Int>, MessageDeliveryRecord>()

        override fun insertIfAbsent(record: MessageDeliveryRecord): Boolean =
            rows.putIfAbsent(record.eventId to record.position, record) == null

        override fun update(record: MessageDeliveryRecord) {
            rows[record.eventId to record.position] = record
        }
    }

    private class Channel : MessageDelivery {
        override val channel = MessageChannel.SMS
        val sent = mutableListOf<OutboundMessage>()

        override fun send(message: OutboundMessage): DeliveryReceipt {
            sent += message
            return DeliveryReceipt("d-1")
        }

        override fun status(deliveryId: String) = DeliveryStatus(deliveryId, DeliveryState.SENT)
    }

    private class FakeOpener(
        private val clear: String,
        private val label: String,
        private val records: Records,
        private val channel: Channel,
    ) : PasscodeOpener {
        val calls = mutableListOf<Pair<String, String>>()
        var rowExistedAtOpen = false
        var sentAtOpen = -1

        override fun open(
            sealed: String,
            label: String,
        ): String {
            calls += sealed to label
            rowExistedAtOpen = records.rows.isNotEmpty()
            sentAtOpen = channel.sent.size
            if (label != this.label) throw PasscodeUnopenable()
            return clear
        }
    }

    private class Built(
        val records: Records,
        val channel: Channel,
        val useCase: DeliverRequestedMessage,
        val opener: FakeOpener?,
    )

    private fun build(opener: (Records, Channel) -> PasscodeOpener): Built {
        val records = Records()
        val sms = Channel()
        val o = opener(records, sms)
        val channels =
            object : MessageChannels {
                override fun forChannel(channel: DeliveryChannel) = if (channel == DeliveryChannel.SMS) sms else null
            }
        val templates =
            object : TemplateVersions {
                override fun versionOf(templateId: String) = if (templateId == "signed-link-passcode") "1" else null
            }
        return Built(records, sms, DeliverRequestedMessage(records, channels, templates, clock, o), o as? FakeOpener)
    }

    private fun fake(label: String = recipient): (Records, Channel) -> PasscodeOpener =
        { r, c -> FakeOpener(clear, label, r, c) }

    private fun payload(to: String = recipient) =
        mapOf(
            "template" to "signed-link-passcode",
            "channel" to "SMS",
            "recipients" to listOf(to),
            "sealedPasscode" to sealed,
        )

    @Test
    @DisplayName(
        "INV-AC-26535 the channel receives the clear passcode and the opener got the sealed string " +
            "and the recipient label",
    )
    fun clearToChannel() {
        val b = build(fake())
        b.useCase.handle(tenant, "ent-1", "evt-1", payload())
        b.channel.sent.size shouldBe 1
        b.channel.sent
            .single()
            .content shouldBe PasscodeContent(clear)
        b.opener!!.calls shouldBe listOf(sealed to recipient)
        // Opener-first order: the row exists and nothing was sent when the opener ran.
        b.opener.rowExistedAtOpen shouldBe true
        b.opener.sentAtOpen shouldBe 0
        b.records.rows.values
            .single()
            .state shouldBe DeliveryRecordState.SENT
    }

    @Test
    @DisplayName(
        "INV-AC-26535 no record field, log line or exception text holds the clear passcode " +
            "or the sealed string",
    )
    fun noSecretLeaks() {
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
            val ok = build(fake())
            ok.useCase.handle(tenant, "ent-1", "evt-1", payload())
            val refused = build(fake("someone-else@example.com"))
            refused.useCase.handle(tenant, "ent-1", "evt-1", payload())
            val refusing = build({ _, _ -> RefusingPasscodeOpener })
            refusing.useCase.handle(tenant, "ent-1", "evt-1", payload())
            for (b in listOf(ok, refused, refusing)) {
                b.records.rows.values
                    .forEach { texts += it.toString() }
            }
            texts += shouldThrow<PasscodeUnopenable> { RefusingPasscodeOpener.open(sealed, recipient) }.toString()
        } finally {
            root.removeHandler(handler)
        }
        for (t in texts + lines) {
            for (secret in listOf(clear, sealed)) (secret in t) shouldBe false
        }
    }

    @Test
    @DisplayName("INV-AC-26535 an opener that refuses gives FAILED PASSCODE_UNOPENABLE and no send")
    fun refusalFailsClosed() {
        val b = build(fake("someone-else@example.com"))
        b.useCase.handle(tenant, "ent-1", "evt-1", payload())
        b.channel.sent.size shouldBe 0
        val r =
            b.records.rows.values
                .single()
        r.state shouldBe DeliveryRecordState.FAILED
        r.failureReason shouldBe "PASSCODE_UNOPENABLE"
    }

    @Test
    @DisplayName("INV-AC-26535 a link payload never calls the opener")
    fun linkNeverOpens() {
        val calls = mutableListOf<String>()
        val b =
            build({ _, _ ->
                PasscodeOpener { s, _ ->
                    calls += s
                    clear
                }
            })
        val link =
            mapOf(
                "template" to "signed-link-passcode",
                "channel" to "EMAIL",
                "recipients" to listOf(recipient),
                "linkReference" to "link-ref-1",
            )
        b.useCase.handle(tenant, "ent-1", "evt-1", link)
        calls shouldBe emptyList()
    }

    @Test
    @DisplayName("INV-AC-26535 the default RefusingPasscodeOpener fails closed")
    fun refusingDefault() {
        shouldThrow<PasscodeUnopenable> { RefusingPasscodeOpener.open(sealed, recipient) }.message shouldBe
            PasscodeUnopenable.MESSAGE
        val b = build({ _, _ -> RefusingPasscodeOpener })
        b.useCase.handle(tenant, "ent-1", "evt-1", payload())
        b.channel.sent.size shouldBe 0
        val r =
            b.records.rows.values
                .single()
        r.state shouldBe DeliveryRecordState.FAILED
        r.failureReason shouldBe "PASSCODE_UNOPENABLE"
    }

    @Test
    @DisplayName(
        "INV-AC-26535 the clear passcode is not a field of a stored record " +
            "(the record type has no member that equals it)",
    )
    fun recordHasNoClearMember() {
        val b = build(fake())
        b.useCase.handle(tenant, "ent-1", "evt-1", payload())
        val r =
            b.records.rows.values
                .single()
        val members =
            MessageDeliveryRecord::class.java.declaredFields.map {
                it.isAccessible = true
                it.get(r)
            }
        members.none { it == clear || it == sealed } shouldBe true
    }
}
