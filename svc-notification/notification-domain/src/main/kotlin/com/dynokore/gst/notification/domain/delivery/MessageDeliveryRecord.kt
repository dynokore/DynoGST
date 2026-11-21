package com.dynokore.gst.notification.domain.delivery

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** The channel a message leaves by (INV-REQ-817); the domain keeps its own copy of the port's enum. */
enum class DeliveryChannel { EMAIL, SMS }

/** The state of one delivery record. */
enum class DeliveryRecordState { QUEUED, SENT, DELIVERED, BOUNCED, FAILED }

/** The reason a record is FAILED or BOUNCED; never the text of an exception. */
object DeliveryFailure {
    const val CHANNEL_NOT_BOUND = "CHANNEL_NOT_BOUND"
    const val TEMPLATE_UNKNOWN = "TEMPLATE_UNKNOWN"
    const val SEND_REFUSED = "SEND_REFUSED"
}

/**
 * One delivery per (event, recipient position). [recipientRef] is a reference, never an address.
 * [failureReason] is non-blank exactly in FAILED and BOUNCED (INV-REQ-817).
 */
data class MessageDeliveryRecord(
    val messageId: UUID,
    val eventId: String,
    val position: Int,
    val entityRef: String,
    val channel: DeliveryChannel,
    val template: String,
    val templateVersion: String?,
    val recipientRef: String,
    val state: DeliveryRecordState,
    val deliveryId: String? = null,
    val failureReason: String? = null,
    val sentAt: Instant? = null,
) {
    init {
        require(position >= 0) { "position must not be negative" }
        val failed = state == DeliveryRecordState.FAILED || state == DeliveryRecordState.BOUNCED
        if (failed) {
            require(!failureReason.isNullOrBlank()) { "failureReason is required in $state" }
        } else {
            require(failureReason == null) { "failureReason must be null in $state" }
        }
    }

    companion object {
        private val NAMESPACE: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

        /** Name-based UUID, version 5 (RFC 9562), of `eventId#position`. */
        fun messageId(
            eventId: String,
            position: Int,
        ): UUID {
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(toBytes(NAMESPACE))
            val h = sha.digest("$eventId#$position".toByteArray(StandardCharsets.UTF_8))
            h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
            h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
            var msb = 0L
            var lsb = 0L
            for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
            for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
            return UUID(msb, lsb)
        }

        /** The reference of the recipient at the zero-based [position]; never an address. */
        fun recipientRef(position: Int): String = "recipient-${position + 1}"

        private fun toBytes(u: UUID): ByteArray {
            val b = ByteArray(16)
            for (i in 0 until 8) b[i] = (u.mostSignificantBits ushr (8 * (7 - i))).toByte()
            for (i in 8 until 16) b[i] = (u.leastSignificantBits ushr (8 * (15 - i))).toByte()
            return b
        }
    }
}
