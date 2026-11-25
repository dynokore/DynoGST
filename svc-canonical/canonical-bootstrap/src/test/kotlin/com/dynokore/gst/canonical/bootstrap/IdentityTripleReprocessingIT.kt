package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.adapters.events.JacksonIdentityTripleDocuments
import com.dynokore.gst.canonical.adapters.persistence.JooqCurrentDocumentVersions
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.reprocessing.CurrentDocumentVersions
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleDocuments
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleReprocessingRetry
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.function.Supplier

/**
 * INV-AC-26792: the consumer of `IdentityTripleReprocessingRequested` in the full TPL-18 context (P109), over the real
 * adapters of the port beans; only the masterdata answer is a stub (the real client calls masterdata-svc).
 */
class IdentityTripleReprocessingIT {
    private val type = CanonicalEvents.IDENTITY_TRIPLE_REPROCESSING_REQUESTED
    private val topic = CanonicalEvents.IDENTITY_TRIPLE_REPROCESSING_TOPIC
    private val day = LocalDate.of(2026, 3, 15)
    private val stored = SupplierIdentity("S-1", "SUP-1", "M1-0000001-X")
    private val resolved = SupplierIdentity("S-2", "SUP-2", "M2-0000002-Y")
    private val json = EnvelopeJson()

    private class Mutable : Clock() {
        var now: Instant = Instant.now()

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private class Log : com.dynokore.platform.outboxandqueue.EventLogReader {
        private val records = mutableListOf<com.dynokore.platform.outboxandqueue.LogRecord>()

        fun add(envelopeJson: String) {
            records +=
                com.dynokore.platform.outboxandqueue
                    .LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ) = records.filter { it.offset > afterOffset }.take(limit)
    }

    private fun runner(
        dir: Path,
        answer: () -> SupplierAnswer,
    ): ApplicationContextRunner =
        CanonicalContext.online(dir).withAllowBeanDefinitionOverriding(true).withBean(
            "aggregateSupplierIdentity",
            AggregateSupplierIdentity::class.java,
            Supplier { AggregateSupplierIdentity { _, _, _ -> answer() } },
            BeanDefinitionCustomizer { it.isPrimary = true },
        )

    private fun canonical(triple: SupplierIdentity) =
        """{"documentNumber":"N-1","identityTriple":{"value":{"senderId":"${triple.senderId}",""" +
            """"supplierId":"${triple.supplierId}","supplierGstn":"${triple.supplierGstn}"},""" +
            """"classification":"IDENTIFYING"},"lines":{"value":[],"classification":"content"},""" +
            """"taxSubtotals":{"value":[],"classification":"content"},""" +
            """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"}}"""

    private fun version() =
        NewVersion(
            canonical(stored),
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            day,
            "svc:x",
        )

    private inner class Harness(
        val context: ApplicationContext,
        answer: () -> SupplierAnswer,
    ) {
        val clock = Mutable()
        val log = Log()
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val t1: UUID = UUID.randomUUID()
        val t2: UUID = UUID.randomUUID()
        val doc1: UUID = UUID.randomUUID()
        val doc2: UUID = UUID.randomUUID()
        val consumerName = IdentityTripleReprocessingConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, topic, "canonical"),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("identityTripleReprocessingRequestedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                clock,
            )

        init {
            seed(t1, doc1)
            seed(t2, doc2)
        }

        private fun seed(
            tenant: UUID,
            doc: UUID,
        ) {
            val entity = UUID.randomUUID()
            val periodId = UUID.randomUUID()
            owner(
                "insert into canonical.period_span (tenant_id, entity_ref, period_ref, period_start, period_end, " +
                    "period_id) values ('$tenant', 'ENT-1', '2026-Q1', '2026-01-01', '2026-03-31', '$periodId')",
            )
            val document =
                NewDocument(
                    doc,
                    tenant,
                    entity,
                    null,
                    Direction.PURCHASE,
                    380,
                    "INV-$doc",
                    "issuer-1",
                    2026,
                    null,
                    DocumentState.READY,
                    periodId,
                    version(),
                )
            scope.bind(TenantId(tenant), SignedBindings.ACTOR) {
                val link = seededLink(tenant, doc)
                runner.inTransaction { JooqDocumentStore(dsl).insert(document, link) }
            }
            owner(
                "insert into canonical.inbound_event (tenant_id, entity_id, doc_uuid, version, event_kind, " +
                    "document_function) values ('$tenant', '$entity', '$doc', 1, 'POSTING', 'INVOICE')",
            )
        }

        fun send(
            tenant: UUID,
            eventId: String,
            data: String,
        ) = log.add(
            json.write(
                Envelope(
                    id = EventId(eventId),
                    source = "/gst/submission-svc",
                    type = type,
                    subject = "ENT-1",
                    time = clock.instant(),
                    tenant = TenantId(tenant),
                    entity = EntityRef("ENT-1"),
                    docUuid = null,
                    transactionDate = null,
                    docSequence = null,
                    instanceId = "submission-svc-0",
                    correlation = CorrelationId("corr-$eventId"),
                    causation = CausationId("cause-$eventId"),
                    dataJson = data,
                ),
            ),
        )

        fun data(vararg docs: UUID) =
            """{"entityRef":"ENT-1","constitution":"SOLE","effectiveFrom":"2026-01-01","changeRef":"CH-1",""" +
                """"docUuids":[${docs.joinToString(",") { "\"$it\"" }}]}"""

        fun versions(doc: UUID) = count("select count(*) from canonical.document_version where doc_uuid = '$doc'")

        fun created(doc: UUID) =
            count(
                "select count(*) from canonical.outbox where doc_uuid = '$doc' " +
                    "and type like '%CanonicalVersionCreated%'",
            )

        fun markers() = count("select count(*) from canonical.identity_triple_request_marker where tenant_id = '$t1'")

        fun currentCanonical(doc: UUID): String =
            checkNotNull(
                rows(
                    "select canonical::text from canonical.document_version where doc_uuid = '$doc' " +
                        "order by version desc limit 1",
                ).single().single(),
            )

        fun poll(): PollResult = consumer.pollOnce()

        init {
            answer()
        }
    }

    private fun owner(sql: String) {
        CanonicalDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }
    }

    private fun rows(sql: String): List<List<String?>> =
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    val width = r.metaData.columnCount
                    buildList { while (r.next()) add((1..width).map { c -> r.getString(c) }) }
                }
            }
        }

    private fun count(sql: String) = rows(sql).single().single()!!.toInt()

    private fun run(
        dir: Path,
        answer: () -> SupplierAnswer = { SupplierAnswer.Found(resolved) },
        block: Harness.() -> Unit,
    ) {
        runner(dir, answer).run { context -> Harness(context, answer).block() }
    }

    private fun startupFailure(
        dir: Path,
        catalogue: EventCatalogue,
    ): String? {
        var failure: Throwable? = null
        CanonicalContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean("eventCatalogue", EventCatalogue::class.java, Supplier { catalogue })
            .run { context: AssertableApplicationContext -> failure = context.startupFailure }
        val chain = generateSequence(failure) { it.cause }
        return chain.joinToString(" | ") { it.message.orEmpty() }.takeIf { failure != null }
    }

    @Test
    fun `INV-AC-26792 case 1 and 12 one event of T1 stores one new version and stages one CanonicalVersionCreated`(
        @TempDir dir: Path,
    ) = run(dir) {
        (context.getBean(CurrentDocumentVersions::class.java) is JooqCurrentDocumentVersions) shouldBe true
        (context.getBean(IdentityTripleDocuments::class.java) is JacksonIdentityTripleDocuments) shouldBe true
        send(t1, "ev-1", data(doc1))
        poll() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        versions(doc1) shouldBe 2
        created(doc1) shouldBe 1
        JacksonIdentityTripleDocuments().tripleOf(currentCanonical(doc1)) shouldBe resolved
    }

    @Test
    fun `INV-AC-26792 case 2 the same CloudEvents id delivered twice leaves one new version`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(t1, "ev-2", data(doc1))
        send(t1, "ev-2", data(doc1))
        poll().total shouldBe 2
        versions(doc1) shouldBe 2
        created(doc1) shouldBe 1
        markers() shouldBe 1
    }

    @Test
    fun `INV-AC-26792 case 3 an event of tenant T2 naming a document of T1 changes no document of T1`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(t2, "ev-3", data(doc1))
        poll().total shouldBe 1
        versions(doc1) shouldBe 1
        created(doc1) shouldBe 0
        versions(doc2) shouldBe 1
        JacksonIdentityTripleDocuments().tripleOf(currentCanonical(doc1)) shouldBe stored
    }

    @Test
    fun `INV-AC-26792 case 4 a catalogue without the topic fails the context start`(
        @TempDir dir: Path,
    ) {
        startupFailure(dir, EventCatalogue(CanonicalEvents.entries)) shouldContain "does not list $type on $topic"
    }

    @Test
    fun `INV-AC-26792 case 5 a catalogue that lists the topic for another event type fails the context start`(
        @TempDir dir: Path,
    ) {
        val lookAlike =
            CatalogueEntry(
                "com.dynokore.gst.submission.IdentityTripleReprocessingRequested.v2",
                topic,
                setOf("submission-svc"),
                contentTopic = false,
            )
        startupFailure(dir, EventCatalogue(CanonicalEvents.entries + lookAlike)) shouldContain
            "does not list $type on $topic"
    }

    @Test
    fun `INV-AC-26792 case 6 a handler retry outcome ends in a redelivery and no marker row`(
        @TempDir dir: Path,
    ) {
        var answer: SupplierAnswer = SupplierAnswer.Retry("masterdata unavailable")
        run(dir, { answer }) {
            send(t1, "ev-6", data(doc1))
            shouldThrow<IdentityTripleReprocessingRetry> { poll() }
            markers() shouldBe 0
            count("select count(*) from canonical.inbox where consumer = '$consumerName'") shouldBe 0
            versions(doc1) shouldBe 1
            count("select count(*) from canonical.event_dead_letter where consumer = '$consumerName'") shouldBe 0
            answer = SupplierAnswer.Found(resolved)
            clock.now = clock.now.plus(Duration.ofHours(1))
            poll() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
            versions(doc1) shouldBe 2
            markers() shouldBe 1
        }
    }

    @Test
    fun `INV-AC-26792 case 7 a payload without docUuids is refused as a malformed payload`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(t1, "ev-7", """{"entityRef":"ENT-1"}""")
        poll() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        versions(doc1) shouldBe 1
        markers() shouldBe 0
    }

    @Test
    fun `INV-AC-26792 case 8 the catalogue bean holds the consumed type once and entries does not hold it`(
        @TempDir dir: Path,
    ) = run(dir) {
        context.getBean(EventCatalogue::class.java).entries().count { it.eventType == type } shouldBe 1
        CanonicalEvents.entries.count { it.eventType == type } shouldBe 0
        CanonicalEvents.consumed.map { it.eventType } shouldBe listOf(type)
    }

    @Test
    fun `INV-AC-26792 case 9 emitting the consumed type through the outbox is refused`(
        @TempDir dir: Path,
    ) = run(dir) {
        shouldThrow<EmissionNotPermitted> {
            scope.bind(TenantId(t1), SignedBindings.ACTOR) {
                runner.inTransaction {
                    context.getBean(OutboxWriter::class.java).append(
                        listOf(OutboundEvent(type, "{}")),
                        EventContext(
                            TenantId(t1),
                            EntityRef("ENT-1"),
                            null,
                            null,
                            doc1.toString(),
                            null,
                            CorrelationId("corr-9"),
                            CausationId("cause-9"),
                        ),
                    )
                }
            }
        }
        created(doc1) shouldBe 0
        count("select count(*) from canonical.outbox where tenant_id = '$t1' and type = '$type'") shouldBe 0
    }
}
