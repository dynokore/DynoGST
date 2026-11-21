package com.dynokore.gst.notification.domain.delivery

import java.nio.ByteBuffer
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
        private const val UUID_BYTES = 16
        private const val VERSION_BYTE = 6
        private const val VARIANT_BYTE = 8
        private const val CLEAR_VERSION = 0x0f
        private const val SET_VERSION_5 = 0x50
        private const val CLEAR_VARIANT = 0x3f
        private const val SET_VARIANT = 0x80

        /** Name-based UUID, version 5 (RFC 9562), of `eventId#position`. */
        fun messageId(
            eventId: String,
            position: Int,
        ): UUID {
            // RFC 9562 version 5 names SHA-1; this is an identifier, not a security use.
            // nosemgrep: kotlin.lang.security.use-of-sha1.use-of-sha1
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(
                ByteBuffer
                    .allocate(UUID_BYTES)
                    .putLong(NAMESPACE.mostSignificantBits)
                    .putLong(NAMESPACE.leastSignificantBits)
                    .array(),
            )
            val h = sha.digest("$eventId#$position".toByteArray(StandardCharsets.UTF_8))
            h[VERSION_BYTE] = ((h[VERSION_BYTE].toInt() and CLEAR_VERSION) or SET_VERSION_5).toByte()
            h[VARIANT_BYTE] = ((h[VARIANT_BYTE].toInt() and CLEAR_VARIANT) or SET_VARIANT).toByte()
            val buffer = ByteBuffer.wrap(h)
            return UUID(buffer.getLong(), buffer.getLong())
        }

        /** The reference of the recipient at the zero-based [position]; never an address. */
        fun recipientRef(position: Int): String = "recipient-${position + 1}"
    }
}
