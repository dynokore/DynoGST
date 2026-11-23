package com.dynokore.gst.canonical.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events canonical-svc may emit (INV-REQ-815 behaviour 2): the six of model/architecture/events.yaml it produces
 * NetDerivationRefused (seven in all) and the platform event PackDryRunSampleRecorded, each on the topic its AsyncAPI
 * channel names. The emission guard of the outbox refuses any other type. The catalogue bean also lists the events
 * canonical-svc consumes ([CanonicalEvents.consumed], produced by another service), so a consumer of one of them starts
 * only when its entry is catalogued (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(CanonicalEvents.entries + CanonicalEvents.consumed)
}

object CanonicalEvents {
    private const val SERVICE = "canonical-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.canonical."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "TransactionRecorded" to "gst.canonical.transaction-recorded.v1",
            "CanonicalVersionCreated" to "gst.canonical.canonical-version-created.v1",
            "DecisionTraceRecorded" to "gst.canonical.decision-trace-recorded.v1",
            "DocumentHeld" to "gst.canonical.document-held.v1",
            "DocumentReleased" to "gst.canonical.document-released.v1",
            "PeriodReassigned" to "gst.canonical.period-reassigned.v1",
            "NetDerivationRefused" to "gst.canonical.net-derivation-refused.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    /** The platform event canonical-svc emits besides its own (INV-REQ-772 b1): one page of the dry-run sample, and
     * (INV-REQ-1003) one page of the population of a reprocessing run. */
    val platformTopicByType: Map<String, String> =
        mapOf(
            "com.dynokore.gst.platform.PackDryRunSampleRecorded.v1" to "gst.platform.pack-dry-run-sample-recorded.v1",
            "com.dynokore.gst.platform.PackReprocessingPopulationRecorded.v1" to
                "gst.platform.pack-reprocessing-population-recorded.v1",
        )

    val entries: List<CatalogueEntry> =
        (topicByType + platformTopicByType).map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }

    /**
     * The events canonical-svc consumes whose type the catalogue must list for the consumer to start (the row of
     * `event-log/topics.yaml` of the pinned bundle). They are not emitted here: the producer is not canonical-svc.
     */
    val consumed: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                IDENTITY_TRIPLE_REPROCESSING_REQUESTED,
                IDENTITY_TRIPLE_REPROCESSING_TOPIC,
                setOf("submission-svc"),
                contentTopic = false,
            ),
        )

    const val IDENTITY_TRIPLE_REPROCESSING_REQUESTED =
        "com.dynokore.gst.submission.IdentityTripleReprocessingRequested.v1"
    const val IDENTITY_TRIPLE_REPROCESSING_TOPIC = "gst.submission.identity-triple-reprocessing-requested.v1"
}
