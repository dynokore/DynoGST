package com.dynokore.gst.submission.application.arrival

import com.dynokore.platform.outboxandqueue.DependencyUnavailable

/**
 * A fact the arrival step needs cannot be read now. It carries [DependencyUnavailable], so the consumer classifies it
 * as an infrastructure failure: the delivery rolls back and is retried.
 */
class CollaboratorUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause),
    DependencyUnavailable
