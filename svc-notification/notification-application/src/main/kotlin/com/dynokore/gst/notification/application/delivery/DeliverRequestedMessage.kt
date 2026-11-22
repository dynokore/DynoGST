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
import com.dynokore.platform.serviceruntime.binding.TemplateValuesContent
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
    private val passcodes: PasscodeOpener,
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
        val version = templates.versionOf(request.template)
        val channel = if (version == null) null else channels.forChannel(request.channel)
        return when {
            version == null -> failed(base, null, DeliveryFailure.TEMPLATE_UNKNOWN)
            channel == null -> failed(base, version, DeliveryFailure.CHANNEL_NOT_BOUND)
            else -> openAndSend(base, channel, request, address, version)
        }
    }

    // The opener runs after the record row exists and before send; the clear value lives in this call only.
    private fun openAndSend(
        base: MessageDeliveryRecord,
        channel: MessageDelivery,
        request: Request,
        address: String,
        version: String,
    ): MessageDeliveryRecord {
        val clear =
            if (request.sealedPasscode == null) {
                null
            } else {
                try {
                    passcodes.open(request.sealedPasscode, address)
                } catch (_: PasscodeUnopenable) {
                    return failed(base, version, PASSCODE_UNOPENABLE)
                }
            }
        return send(base, channel, outbound(request, address, version, clear), version)
    }

    private fun outbound(
        request: Request,
        address: String,
        version: String,
        clearPasscode: String?,
    ) = OutboundMessage(
        channel = MessageChannel.valueOf(request.channel.name),
        to = address,
        templateId = request.template,
        templateVersion = version,
        content =
            when {
                request.values != null -> TemplateValuesContent(request.values)
                clearPasscode != null -> PasscodeContent(clearPasscode)
                else -> LinkContent(requireNotNull(request.linkReference))
            },
    )

    private fun send(
        base: MessageDeliveryRecord,
        channel: MessageDelivery,
        message: OutboundMessage,
        version: String,
    ): MessageDeliveryRecord {
        // The exception text is never copied: it may quote the address or the passcode.
        val receipt: DeliveryReceipt? =
            try {
                channel.send(message)
            } catch (_: Exception) {
                null
            }
        return if (receipt == null) {
            failed(base, version, DeliveryFailure.SEND_REFUSED)
        } else {
            base.copy(
                templateVersion = version,
                state = DeliveryRecordState.SENT,
                deliveryId = receipt.deliveryId,
                sentAt = clock.instant(),
            )
        }
    }

    private fun failed(
        base: MessageDeliveryRecord,
        version: String?,
        reason: String,
    ) = base.copy(templateVersion = version, state = DeliveryRecordState.FAILED, failureReason = reason)
}

/** The reason of a record whose sealed passcode could not be opened. */
private const val PASSCODE_UNOPENABLE = "PASSCODE_UNOPENABLE"

private class Request(
    val template: String,
    val channel: DeliveryChannel,
    val recipients: List<String>,
    val linkReference: String?,
    val sealedPasscode: String?,
    val values: Map<String, String>?,
)

/** The templates whose content is a closed set of template values: template id to its exact key set. */
val TEMPLATE_VALUE_KEYS: Map<String, Set<String>> =
    mapOf("retention-notice" to setOf("periodStart", "periodEnd", "noticeWindowEndsAt"))

// Messages name the field only, never a value.
private fun parse(payload: Map<String, Any?>): Request {
    val channel = channelOf(requiredText(payload, "channel"))
    val recipients = recipientsOf(payload)
    val template = requiredText(payload, "template")
    val link = optional(payload, "linkReference")
    val passcode = optional(payload, "sealedPasscode")
    val raw = payload["templateParameters"]
    checkContent(link, passcode, raw)
    checkShape(channel, passcode, recipients.size)
    val values = if (raw != null) valuesOf(template, raw, channel, recipients.size) else null
    if (values == null && template in TEMPLATE_VALUE_KEYS) {
        throw ContractViolation("this template takes templateParameters only")
    }
    return Request(template, channel, recipients, link, passcode, values)
}

private fun checkContent(
    link: String?,
    passcode: String?,
    parameters: Any?,
) {
    if (listOf(link, passcode, parameters).count { it != null } != 1) {
        throw ContractViolation("exactly one of linkReference, sealedPasscode and templateParameters is required")
    }
}

private fun valuesOf(
    template: String,
    raw: Any,
    channel: DeliveryChannel,
    recipientCount: Int,
): Map<String, String> {
    val keys = TEMPLATE_VALUE_KEYS[template] ?: throw ContractViolation("this template takes no templateParameters")
    if (channel != DeliveryChannel.EMAIL || recipientCount != 1) {
        throw ContractViolation("templateParameters are carried on EMAIL to one recipient only")
    }
    return textValues(raw as? Map<*, *>, keys)
}

private fun textValues(
    map: Map<*, *>?,
    keys: Set<String>,
): Map<String, String> {
    if (map == null || map.keys != keys) {
        throw ContractViolation("templateParameters is not an object with the key set of the template")
    }
    return map.entries.associate { (k, v) ->
        (k as String) to ((v as? String)?.takeIf { it.isNotBlank() } ?: throw ContractViolation("a value is not text"))
    }
}

private fun checkShape(
    channel: DeliveryChannel,
    passcode: String?,
    recipientCount: Int,
) {
    if (channel == DeliveryChannel.SMS && passcode == null) {
        throw ContractViolation("an SMS message carries a sealedPasscode only")
    }
    if (passcode != null && recipientCount > 1) {
        throw ContractViolation("a sealedPasscode message has one recipient only")
    }
}

private fun channelOf(name: String): DeliveryChannel =
    DeliveryChannel.entries.firstOrNull { it.name == name }
        ?: throw ContractViolation("channel is not a known channel")

private fun recipientsOf(payload: Map<String, Any?>): List<String> {
    val raw = payload["recipients"] as? List<*> ?: throw ContractViolation("recipients is missing or not a list")
    if (raw.isEmpty()) throw ContractViolation("recipients is empty")
    return raw.map { recipientOf(it) }
}

private fun recipientOf(value: Any?): String =
    (value as? String)?.takeIf { it.isNotBlank() } ?: throw ContractViolation("a recipient is blank")

private fun requiredText(
    payload: Map<String, Any?>,
    name: String,
): String =
    (payload[name] as? String)?.takeIf { it.isNotBlank() }
        ?: throw ContractViolation("$name is missing or blank")

private fun optional(
    payload: Map<String, Any?>,
    name: String,
): String? {
    val v = payload[name] ?: return null
    return (v as? String)?.takeIf { it.isNotBlank() } ?: throw ContractViolation("$name is blank or not text")
}
