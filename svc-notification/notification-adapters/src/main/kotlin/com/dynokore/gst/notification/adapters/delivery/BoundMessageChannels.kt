package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.gst.notification.application.delivery.MessageChannels
import com.dynokore.gst.notification.domain.delivery.DeliveryChannel
import com.dynokore.platform.serviceruntime.binding.MessageChannel
import com.dynokore.platform.serviceruntime.binding.MessageDelivery

/**
 * The `MessageDelivery` beans of the context, selected by channel (INV-REQ-817). No bean for a channel gives null, so
 * the use case records FAILED CHANNEL_NOT_BOUND (fail closed). Two beans for one channel is a wiring error.
 */
class BoundMessageChannels(
    bound: List<MessageDelivery>,
) : MessageChannels {
    private val byChannel: Map<MessageChannel, MessageDelivery> =
        bound.groupBy { it.channel }.mapValues { (channel, beans) ->
            check(beans.size == 1) { "more than one MessageDelivery is bound for $channel" }
            beans.single()
        }

    override fun forChannel(channel: DeliveryChannel): MessageDelivery? = byChannel[MessageChannel.valueOf(channel.name)]
}
