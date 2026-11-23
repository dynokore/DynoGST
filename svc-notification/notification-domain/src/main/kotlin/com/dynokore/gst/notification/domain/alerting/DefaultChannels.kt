package com.dynokore.gst.notification.domain.alerting

/** Contract member name of AlertPolicy.channels (platform-contracts notification/openapi.yaml). */
const val CHANNEL_EMAIL = "EMAIL"

/** INV-REQ-1307: the default channel list of the deadline alert class is email only. */
val DEADLINE_DEFAULT_CHANNELS: List<String> = listOf(CHANNEL_EMAIL)
