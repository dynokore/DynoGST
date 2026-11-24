package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.application.index.IndexMetrics
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The seven index consumers of the bootstrap context against a real database (INV-AC-26810): the real subscription
 * and handler beans driven by the library `JdbcInboxConsumer` over fixed lists of records, as
 * [FailureConsumersTest] does. Rows are read as `console_bff_runtime` under a signed binding.
 */
class DocumentIndexConsumersTest {
    private val json = EnvelopeJson()
    private val time = Instant.parse("2026-11-12T08:15:30Z")
    private val doc = UUID.randomUUID()

    private class ListReader(
        val records: MutableList<LogRecord> = mutableListOf(),
    ) : EventLogReader {
        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private inner class Kit(
        val context: ApplicationContext,
    ) {
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        val calls = AtomicInteger()
        private val indexName = "t26816-" + UUID.randomUUID()
        private val exceptionName = "t26816x-" + UUID.randomUUID()
        private val readers = mutableMapOf<String, ListReader>()
        private val offsets = mutableMapOf<String, Long>()

        fun indexSubscriptions(): List<ConsumerSubscription> =
            context
                .getBeansOfType(ConsumerSubscription::class.java)
                .values
                .filter { it.consumer == DocumentIndexConsumerConfiguration.INDEX_CONSUMER }

        private fun counting(real: EventHandler) =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls.incrementAndGet()
                    return real.handle(event)
                }
            }

        private fun deliver(
            subscriptionBean: String,
            handlerBean: String,
            name: String,
            envelope: Envelope,
        ) = run {
            val subscription = context.getBean(subscriptionBean, ConsumerSubscription::class.java)
            val reader = readers.getOrPut(name + subscription.topic) { ListReader() }
            val offset = offsets.merge(name + subscription.topic, 1L, Long::plus)!!
            reader.records += LogRecord(offset, json.write(envelope))
            JdbcInboxConsumer(
                ConsumerSubscription(name, subscription.topic, subscription.schema),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
                counting(context.getBean(handlerBean, EventHandler::class.java)),
                RebuildRequiredSink { },
            ).pollOnce()
        }

        /** Delivers [envelope] to the index consumer of [kind] (the bean name stem). */
        fun index(
            kind: String,
            envelope: Envelope,
        ) = deliver("index${kind}Subscription", "index${kind}Handler", indexName, envelope)

        /** Delivers a DocumentHeld or SubmissionFailed envelope to the existing exception consumers. */
        fun exceptionHeld(envelope: Envelope) =
            deliver("documentHeldSubscription", "documentHeldHandler", exceptionName, envelope)

        fun exceptionFailed(envelope: Envelope) =
            deliver("submissionFailedSubscription", "submissionFailedHandler", exceptionName, envelope)
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        type: String,
        data: String,
        doc: UUID = this.doc,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/test-svc",
        type = type,
        subject = "doc-1",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = DocUuid(doc),
        transactionDate = null,
        docSequence = SEQUENCE.getValue(type),
        instanceId = "svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun versionEvent(
        tenant: UUID,
        id: String,
        entityRef: String? = "E-001",
        version: Int = 1,
    ): Envelope {
        val entity = entityRef?.let { """"entityRef":"$it",""" }.orEmpty()
        return envelope(
            tenant,
            id,
            ConsoleEvents.CANONICAL_VERSION_CREATED_TYPE,
            """{"docUuid":"$doc","version":$version,$entity"transactionDate":"2026-03-20",""" +
                """"scopeVerdict":"SUBMITTABLE",""" +
                """"routingFacts":{"direction":"SALE"},"canonicalDocument":{"parties":{"value":{"buyer":""" +
                """{"identifierScheme":"SG_UEN","identifierValue":"201888888A","name":"Buyer Pte Ltd"}}},""" +
                """"documentNumber":{"value":"INV-1"},"documentDate":{"value":"2026-03-20"},""" +
                """"totals":{"value":{"totalIncludingTax":"109.00"}},"taxSubtotals":{"value":[{"taxCode":"SR"}]}}}""",
        )
    }

    private fun heldEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        ConsoleEvents.DOCUMENT_HELD_TYPE,
        """{"docUuid":"$doc","docsequence":1,"holdReasons":["MISSING_TIN"],"prescribedPeriodRef":"2026-Q4"}""",
    )

    private fun releasedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        ConsoleEvents.DOCUMENT_RELEASED_TYPE,
        """{"docUuid":"$doc","docsequence":2,"releasedHoldReasons":["MISSING_TIN"]}""",
    )

    private fun despatchedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(tenant, id, ConsoleEvents.SUBMISSION_DESPATCHED_TYPE, """{"docUuid":"$doc","version":1}""")

    private fun acknowledgedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        ConsoleEvents.SUBMISSION_ACKNOWLEDGED_TYPE,
        """{"docUuid":"$doc","version":1,"acknowledgementId":"ACK-1"}""",
    )

    private fun failedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        ConsoleEvents.SUBMISSION_FAILED_TYPE,
        """{"docUuid":"$doc","version":1,"code":"SUB-REJECTED","failureClass":"SUB-REJECTED",""" +
            """"retryDisposition":"NONE"}""",
    )

    private fun resolvedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        ConsoleEvents.MASTER_DATA_RESOLVED_TYPE,
        """{"docUuids":["$doc"],"resolvedIdentifiers":[{"scheme":"UEN","value":"201999999Z"}]}""",
    )

    private fun <T> bound(
        tenant: UUID,
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.createStatement().use { st ->
                st.executeQuery(sql).use { buildList { while (it.next()) add(read(it)) } }
            }
        }

    /** `version, state, ack, error, uen` rows of the index. */
    private fun indexRows(tenant: UUID): List<List<String?>> =
        bound(
            tenant,
            "select version, state, acknowledgement_id, error_code, counterparty_uen " +
                "from console_bff.document_index order by version",
        ) { r -> (1..5).map { r.getString(it) } }

    private fun exceptionRows(tenant: UUID): List<Pair<String, String>> =
        bound(tenant, "select code, state from console_bff.exception order by code") {
            it.getString(1) to it.getString(2)
        }

    private fun deadLetters(tenant: UUID): Int = bound(tenant, "select 1 from console_bff.event_dead_letter") { 1 }.size

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        val runner = ConsoleContext.online(dir)
        Files.writeString(dir.resolve("tenancy/binding-key-current"), ConsoleDb.BINDING_SECRET)
        runner.run { context ->
            context.startupFailure shouldBe null
            Kit(context).block()
        }
    }

    @Test
    fun `INV-AC-26810 the seven valid events each reach their handler once and the duplicate is ignored`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        index("CanonicalVersion", versionEvent(a, "v1"))
        val v = versionEvent(a, "v1")
        index("CanonicalVersion", v) // the duplicate event id
        calls.get() shouldBe 1
        indexRows(a) shouldBe listOf(listOf("1", "READY", null, null, "201888888A"))
        index("DocumentHeld", heldEvent(a, "h1"))
        indexRows(a).single()[1] shouldBe "HELD"
        index("DocumentReleased", releasedEvent(a, "r1"))
        indexRows(a).single()[1] shouldBe "READY"
        index("SubmissionDespatched", despatchedEvent(a, "d1"))
        indexRows(a).single()[1] shouldBe "SENT"
        index("SubmissionAcknowledged", acknowledgedEvent(a, "k1"))
        indexRows(a).single().take(3) shouldBe listOf("1", "ACKNOWLEDGED", "ACK-1")
        index("SubmissionFailed", failedEvent(a, "f1"))
        indexRows(a).single()[3] shouldBe "SUB-REJECTED"
        index("MasterDataResolved", resolvedEvent(a, "m1"))
        calls.get() shouldBe 7
        indexRows(a) shouldBe listOf(listOf("1", "ACKNOWLEDGED", "ACK-1", "SUB-REJECTED", "201999999Z"))
        deadLetters(a) shouldBe 0
    }

    @Test
    fun `INV-AC-26810 the malformed version event is dead-lettered and nothing is written`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val bad = envelope(a, "bad", ConsoleEvents.CANONICAL_VERSION_CREATED_TYPE, """{"docUuid":"$doc","version":0}""")
        index("CanonicalVersion", bad).deadLettered shouldBe 1
        indexRows(a) shouldBe emptyList()
        deadLetters(a) shouldBe 1
    }

    @Test
    fun `INV-AC-26810 the exception queue still receives its events beside the index consumers`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        index("CanonicalVersion", versionEvent(a, "v2"))
        index("DocumentHeld", heldEvent(a, "h2"))
        exceptionHeld(heldEvent(a, "h2"))
        index("SubmissionFailed", failedEvent(a, "f2"))
        exceptionFailed(failedEvent(a, "f2"))
        indexRows(a).single().take(2) shouldBe listOf("1", "HELD")
        exceptionRows(a) shouldBe listOf("MISSING_TIN" to "OPEN", "SUB-REJECTED" to "OPEN")
    }

    @Test
    fun `INV-AC-26810 the index consumer name differs from the exception consumer name`(
        @TempDir dir: Path,
    ) = run(dir) {
        val name = DocumentIndexConsumerConfiguration.INDEX_CONSUMER
        (name == ExceptionConsumerConfiguration.CONSUMER) shouldBe false
        (name == ExceptionConsumerConfiguration.CONSUMER.uppercase()) shouldBe false
        (name.equals(ExceptionConsumerConfiguration.CONSUMER, ignoreCase = true)) shouldBe false
        indexSubscriptions().map { it.topic }.toSet() shouldBe
            setOf(
                "gst.canonical.canonical-version-created.v1",
                "gst.canonical.document-held.v1",
                "gst.canonical.document-released.v1",
                "gst.submission.submission-despatched.v1",
                "gst.submission.submission-acknowledged.v1",
                "gst.submission.submission-failed.v1",
                "gst.masterdata.master-data-resolved.v1",
            )
        indexSubscriptions().size shouldBe 7
        indexSubscriptions().map { it.schema }.toSet() shouldBe setOf("console_bff")
    }

    @Test
    fun `INV-AC-26810 the catalogue lists the ten entries with their contract fields`(
        @TempDir dir: Path,
    ) = run(dir) {
        val catalogue = context.getBean(EventCatalogue::class.java)
        expectedCatalogue().forEach { expected ->
            val actual = catalogue.find(expected.eventType)
            actual shouldNotBe null
            actual shouldBe expected
        }
        expectedCatalogue().size shouldBe 10
        ConsoleEvents.consumed.size shouldBe 10
    }

    /** The literal rows of platform-contracts `event-log/topics.yaml` the service consumes. */
    private fun expectedCatalogue(): List<CatalogueEntry> {
        fun entry(
            type: String,
            topic: String,
            producer: String,
            content: Boolean,
        ) = CatalogueEntry(type, topic, setOf(producer), content, recordTopic = false, retainedWhileResolvable = false)
        return listOf(
            entry(
                "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                "gst.canonical.canonical-version-created.v1",
                "canonical-svc",
                true,
            ),
            entry(
                "com.dynokore.gst.submission.SubmissionDespatched.v1",
                "gst.submission.submission-despatched.v1",
                "submission-svc",
                false,
            ),
            entry(
                "com.dynokore.gst.submission.SubmissionAcknowledged.v1",
                "gst.submission.submission-acknowledged.v1",
                "submission-svc",
                false,
            ),
        ) + existingCatalogue()
    }

    /** The seven rows the catalogue held before the index consumers: type, topic, producer, content topic. */
    private fun existingCatalogue(): List<CatalogueEntry> =
        listOf(
            listOf("canonical.DocumentHeld", "canonical.document-held", "canonical-svc", false),
            listOf("canonical.DocumentReleased", "canonical.document-released", "canonical-svc", false),
            listOf("masterdata.MasterDataResolved", "masterdata.master-data-resolved", "masterdata-svc", false),
            listOf("tenancy.PeriodDueDateChanged", "tenancy.period-due-date-changed", "tenancy-svc", false),
            listOf("tenancy.PeriodRecordsChanged", "tenancy.period-records-changed", "tenancy-svc", false),
            listOf("payload.PayloadRejected", "payload.payload-rejected", "payload-svc", true),
            listOf("submission.SubmissionFailed", "submission.submission-failed", "submission-svc", false),
        ).map { row ->
            CatalogueEntry(
                "com.dynokore.gst.${row[0]}.v1",
                "gst.${row[1]}.v1",
                setOf(row[2] as String),
                row[3] as Boolean,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
        }

    @Test
    fun `INV-AC-26810 a version event without entityRef writes no row and counts the unscoped metric once`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        context.getBean(IndexMetrics::class.java) shouldNotBe null
        val counter = context.getBean(MeterRegistry::class.java).counter(UNSCOPED_METRIC)
        val before = counter.count()
        index("CanonicalVersion", versionEvent(a, "u1", entityRef = null))
        indexRows(a) shouldBe emptyList()
        counter.count() - before shouldBe 1.0
        deadLetters(a) shouldBe 0
    }

    private companion object {
        const val UNSCOPED_METRIC = "console_bff_index_unscoped_version_events_total"

        /** The inbox drops an envelope whose doc sequence is not above the last of its Doc UUID: one per type. */
        val SEQUENCE =
            listOf(
                ConsoleEvents.CANONICAL_VERSION_CREATED_TYPE,
                ConsoleEvents.DOCUMENT_HELD_TYPE,
                ConsoleEvents.DOCUMENT_RELEASED_TYPE,
                ConsoleEvents.SUBMISSION_DESPATCHED_TYPE,
                ConsoleEvents.SUBMISSION_ACKNOWLEDGED_TYPE,
                ConsoleEvents.SUBMISSION_FAILED_TYPE,
                ConsoleEvents.MASTER_DATA_RESOLVED_TYPE,
            ).withIndex().associate { (i, type) -> type to i + 1L }
    }
}
