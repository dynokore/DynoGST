package com.dynokore.gst.notification.domain.webhook

/** VP-47 option keys (INV-REQ-1308): customer webhooks off, or subscriptions by event type. */
enum class SubscribeOption { NONE, BY_EVENT_TYPE }

enum class SubscribeRefusal { POINT_PLANNED, NO_EVENT_TYPE }

sealed interface SubscribeDecision {
    data class Accepted(
        val eventType: String,
    ) : SubscribeDecision

    data class Refused(
        val reason: SubscribeRefusal,
    ) : SubscribeDecision
}

/**
 * Pure VP-47 rule (INV-AC-2295): reads no clock, role, store or secret.
 * The vp-47.set permission is checked by the caller.
 */
object WebhookSubscribeRule {
    val DEFAULT = SubscribeOption.NONE

    fun decide(
        option: SubscribeOption,
        eventType: String?,
        pointAvailable: Boolean,
    ): SubscribeDecision {
        val type = eventType?.takeIf { it.isNotBlank() }
        val refusal =
            when {
                option == SubscribeOption.NONE -> SubscribeRefusal.NO_EVENT_TYPE
                !pointAvailable -> SubscribeRefusal.POINT_PLANNED
                type == null -> SubscribeRefusal.NO_EVENT_TYPE
                else -> null
            }
        return if (refusal == null && type != null) {
            SubscribeDecision.Accepted(type)
        } else {
            SubscribeDecision.Refused(refusal ?: SubscribeRefusal.NO_EVENT_TYPE)
        }
    }
}
