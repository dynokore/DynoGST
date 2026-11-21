package com.dynokore.gst.notification.domain.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class MessageDeliveryRecordTest {
    private fun record(
        state: DeliveryRecordState,
        reason: String?,
    ) = MessageDeliveryRecord(
        messageId = MessageDeliveryRecord.messageId("e-1", 0),
        eventId = "e-1",
        position = 0,
        entityRef = "ent-1",
        channel = DeliveryChannel.EMAIL,
        template = "signed-link",
        templateVersion = "1",
        recipientRef = MessageDeliveryRecord.recipientRef(0),
        state = state,
        failureReason = reason,
        sentAt = Instant.parse("2026-11-21T00:00:00Z"),
    )

    @Test
    @DisplayName("INV-AC-26362 a FAILED or BOUNCED record with a null or blank failureReason is refused")
    fun failedNeedsReason() {
        for (s in listOf(DeliveryRecordState.FAILED, DeliveryRecordState.BOUNCED)) {
            for (r in listOf(null, "", "  ")) {
                shouldThrow<IllegalArgumentException> { record(s, r) }
            }
            record(s, "SEND_REFUSED").failureReason shouldBe "SEND_REFUSED"
        }
    }

    @Test
    @DisplayName("INV-AC-26362 a QUEUED, SENT or DELIVERED record with a failureReason is refused")
    fun otherStatesRefuseReason() {
        for (s in listOf(DeliveryRecordState.QUEUED, DeliveryRecordState.SENT, DeliveryRecordState.DELIVERED)) {
            shouldThrow<IllegalArgumentException> { record(s, "SEND_REFUSED") }
            shouldThrow<IllegalArgumentException> { record(s, "") }
            record(s, null).state shouldBe s
        }
    }

    @Test
    @DisplayName("INV-AC-26362 messageId is stable per event and position and differs between positions")
    fun messageIdIsNameBased() {
        MessageDeliveryRecord.messageId("e-1", 0) shouldBe MessageDeliveryRecord.messageId("e-1", 0)
        MessageDeliveryRecord.messageId("e-1", 0) shouldNotBe MessageDeliveryRecord.messageId("e-1", 1)
        MessageDeliveryRecord.messageId("e-1", 0) shouldNotBe MessageDeliveryRecord.messageId("e-2", 0)
        MessageDeliveryRecord.messageId("e-1", 0).version() shouldBe 5
        MessageDeliveryRecord.messageId("e-1", 0).variant() shouldBe 2
    }

    @Test
    @DisplayName("INV-AC-26362 recipientRef is recipient- plus the one-based position")
    fun recipientRefIsNotAnAddress() {
        MessageDeliveryRecord.recipientRef(0) shouldBe "recipient-1"
        MessageDeliveryRecord.recipientRef(1) shouldBe "recipient-2"
    }
}
