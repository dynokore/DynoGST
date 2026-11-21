package com.dynokore.gst.notification.application.delivery

import com.dynokore.gst.notification.application.alerting.ContractViolation
import com.dynokore.gst.notification.domain.delivery.DeliveryChannel
import com.dynokore.gst.notification.domain.delivery.DeliveryFailure
import com.dynokore.gst.notification.domain.delivery.DeliveryRecordState
import com.dynokore.gst.notification.domain.delivery.MessageDeliveryRecord
import com.dynokore.platform.serviceruntime.binding.DeliveryReceipt
import com.dynokore.platform.serviceruntime.binding.LinkContent
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery
import com.dynokore.platform.serviceruntime.binding.OutboundMessage
import com.dynokore.platform.serviceruntime.binding.PasscodeContent
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/** The delivery records; one row per (event, position). */
interface MessageDeliveryRecords {
    /** True when the row was inserted, false when it already exists. */
    fun insertIfAbsent(record: MessageDeliveryRecord): Boolean

    fun update(record: MessageDeliveryRecord)
}

/** The bound implementation per channel. */
interface MessageChannels {
    /** Null when no implementation is bound for [channel]. */
    fun forChannel(channel: DeliveryChannel): MessageDelivery?
}

/** The version of a message template. */
interface TemplateVersions {
    /** Null for an unknown template. */
    fun versionOf(templateId: String): String?
}

/**
 * Delivers a MessageRequested through the bound MessageDelivery channel, one idempotent record per
 * recipient (INV-REQ-817, INV-REQ-2762). Addresses and the sealed passcode are held in memory only,
 * as the outbound message; they are never stored, logged or put in an exception text.
 */
class DeliverRequestedMessage(
    private val records: MessageDeliveryRecords,
    private val channels: MessageChannels,
    private val templates: TemplateVersions,
    private val clock: Clock,
) {
    @Suppress("UNUSED_PARAMETER")
    fun handle(
        tenant: TenantId,
        entityRef: String,
        eventId: String,
        payload: Map<String, Any?>,
    ) {
        val request = parse(payload)
        request.recipients.forEachIndexed { position, address ->
            val base =
                MessageDeliveryRecord(
                    messageId = MessageDeliveryRecord.messageId(eventId, position),
                    eventId = eventId,
                    position = position,
                    entityRef = entityRef,
                    channel = request.channel,
                    template = request.template,
                    templateVersion = null,
                    recipientRef = MessageDeliveryRecord.recipientRef(position),
                    state = DeliveryRecordState.QUEUED,
                )
            if (!records.insertIfAbsent(base)) return@forEachIndexed
            records.update(deliver(base, request, address))
        }
    }

    private fun deliver(
        base: MessageDeliveryRecord,
        request: Request,
        address: String,
    ): MessageDeliveryRecord {
        val version =
            templates.versionOf(request.template)
                ?: return failed(base, null, DeliveryFailure.TEMPLATE_UNKNOWN)
        val channel =
            channels.forChannel(request.channel)
                ?: return failed(base, version, DeliveryFailure.CHANNEL_NOT_BOUND)
        val message =
            OutboundMessage(
                channel = MessageChannel.valueOf(request.channel.name),
                to = address,
                templateId = request.template,
                templateVersion = version,
                content =
                    if (request.sealedPasscode != null) {
                        PasscodeContent(request.sealedPasscode)
                    } else {
                        LinkContent(requireNotNull(request.linkReference))
                    },
            )
        val receipt: DeliveryReceipt =
            try {
                channel.send(message)
            } catch (_: Exception) {
                return failed(base, version, DeliveryFailure.SEND_REFUSED)
            }
        return base.copy(
            templateVersion = version,
            state = DeliveryRecordState.SENT,
            deliveryId = receipt.deliveryId,
            sentAt = clock.instant(),
        )
    }

    private fun failed(
        base: MessageDeliveryRecord,
        version: String?,
        reason: String,
    ) = base.copy(templateVersion = version, state = DeliveryRecordState.FAILED, failureReason = reason)

    private class Request(
        val template: String,
        val channel: DeliveryChannel,
        val recipients: List<String>,
        val linkReference: String?,
        val sealedPasscode: String?,
    )

    // Messages name the field only, never a value.
    private fun parse(payload: Map<String, Any?>): Request {
        val template = text(payload, "template") ?: throw ContractViolation("template is missing or blank")
        val channelText = text(payload, "channel") ?: throw ContractViolation("channel is missing or blank")
        val channel =
            DeliveryChannel.entries.firstOrNull { it.name == channelText }
                ?: throw ContractViolation("channel is not a known channel")
        val raw = payload["recipients"] as? List<*> ?: throw ContractViolation("recipients is missing or not a list")
        if (raw.isEmpty()) throw ContractViolation("recipients is empty")
        val recipients =
            raw.map { r ->
                (r as? String)?.takeIf { it.isNotBlank() }
                    ?: throw ContractViolation("a recipient is blank or not text")
            }
        val link = optional(payload, "linkReference")
        val passcode = optional(payload, "sealedPasscode")
        if (link == null &&
            passcode == null
        ) {
            throw ContractViolation("neither linkReference nor sealedPasscode is present")
        }
        if (link != null &&
            passcode != null
        ) {
            throw ContractViolation("linkReference and sealedPasscode are both present")
        }
        if (channel == DeliveryChannel.SMS && passcode == null) {
            throw ContractViolation("an SMS message carries a sealedPasscode only")
        }
        if (passcode != null && recipients.size > 1) {
            throw ContractViolation("a sealedPasscode message has one recipient only")
        }
        return Request(template, channel, recipients, link, passcode)
    }

    private fun text(
        payload: Map<String, Any?>,
        name: String,
    ): String? = (payload[name] as? String)?.takeIf { it.isNotBlank() }

    private fun optional(
        payload: Map<String, Any?>,
        name: String,
    ): String? {
        val v = payload[name] ?: return null
        return (v as? String)?.takeIf { it.isNotBlank() } ?: throw ContractViolation("$name is blank or not text")
    }
}
