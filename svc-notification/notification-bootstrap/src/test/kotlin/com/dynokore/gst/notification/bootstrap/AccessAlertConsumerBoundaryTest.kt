package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.AlertRecipient.Companion.ALERT_RECIPIENT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Inbox.Companion.INBOX
import com.dynokore.gst.notification.application.alerting.AccessAlertNotDelivered
import com.dynokore.gst.notification.application.alerting.RoleHolders
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
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-27271 through the real inbox consumer (`JdbcInboxConsumer`) with the handler, the use case and the jOOQ
 * adapters of the TPL-18 context over PostgreSQL (P109). Two sources are stubbed at their port: the event-log read
 * (a list in memory) and [RoleHolders] (a primary `withBean` override; replica storage is proven by its own tests).
 */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class AccessAlertConsumerBoundaryTest {
    private val actor = "svc:notification-svc"
    private val at = Instant.parse("2026-11-12T10:00:00Z")
    private val json = EnvelopeJson()
    private var sequence = 0L
    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())

    /** u1 and u2 for T1 by equality on tenant and role string; nothing for T2 or any other role string. */
    private val holders =
        RoleHolders { tenant, role ->
            when {
                tenant == t1 && role == "tenant.admin" -> listOf("u1")
                tenant == t1 && role == "security.officer" -> listOf("u2")
                else -> emptyList()
            }
        }

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Boundary(
        context: ApplicationContext,
        val log: Log,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        val consumerName = AccessAlertConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    AccessAlertConsumerConfiguration.TOPIC,
                    AccessAlertConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("accessAlertRaisedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        val log = Log()
        NotificationContext
            .online(dir)
            .withBean(EventLogReader::class.java, { log }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean(RoleHolders::class.java, { holders }, BeanDefinitionCustomizer { it.isPrimary = true })
            .run { context -> Boundary(context, log).block() }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun event(
        tenant: TenantId,
        eventId: String,
        code: String = "ACC-001",
        entity: String = "E1",
        type: String = "com.dynokore.gst.access.AccessAlertRaised.v1",
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/access-svc",
                type = type,
                subject = entity,
                time = at,
                tenant = tenant,
                entity = EntityRef(entity),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "access-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"accCode":"$code","scope":"entity:$entity"}""",
            ),
        )

    private fun Boundary.alerts(tenant: TenantId): List<List<String?>> =
        tx(tenant) {
            dsl
                .selectFrom(ALERT)
                .fetch()
                .map { listOf(it.state, it.alertClass, it.trigger, it.entityRef) }
        }

    private fun Boundary.recipients(tenant: TenantId): List<String> =
        tx(tenant) {
            dsl
                .selectFrom(ALERT_RECIPIENT)
                .fetch()
                .map { it.userId!! }
                .sorted()
        }

    private fun Boundary.inboxRows(tenant: TenantId) =
        tx(tenant) { dsl.fetchCount(INBOX, INBOX.CONSUMER.eq(consumerName)) }

    private fun Boundary.deadLetters(): List<String?> =
        NotificationDb.superuser().use { c ->
            c.prepareStatement("select reason from notification.event_dead_letter where consumer = ?").use { st ->
                st.setString(1, consumerName)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private val raised = listOf("RAISED", "SECURITY", "ACC-001", "E1")

    @Test
    fun `INV-AC-27271 an ACC-001 event of T1 writes one inbox row, one SECURITY alert for E1 and the recipient rows u1 and u2`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised)
        recipients(t1) shouldBe listOf("u1", "u2")
        inboxRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-27271 the repeated event id writes nothing more`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(event(t1, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe listOf(raised)
        recipients(t1) shouldBe listOf("u1", "u2")
        inboxRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-27271 a second event id for the same entity writes no second alert`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-1"))
        log.add(event(t1, "ev-2"))
        val result = consumer.pollOnce()
        result.applied shouldBe 1
        result.ignored shouldBe 1
        alerts(t1) shouldBe listOf(raised)
        recipients(t1) shouldBe listOf("u1", "u2")
        inboxRows(t1) shouldBe 2
    }

    @Test
    fun `INV-AC-27271 an ACC-001 event of T2 with no holder writes no alert and is not marked processed`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t2, "ev-3"))
        // the platform consumer schedules a retry with backoff and rethrows; it dead-letters only after max attempts
        shouldThrow<AccessAlertNotDelivered> { consumer.pollOnce() }
        alerts(t2) shouldBe emptyList()
        inboxRows(t2) shouldBe 0
        deadLetters() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27271 an ACC-003 event writes no alert row`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-4", code = "ACC-003"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(t1) shouldBe emptyList()
        recipients(t1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27271 no row of class DEADLINE or COVERAGE is written for ACC-001`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-1"))
        consumer.pollOnce().applied shouldBe 1
        alerts(t1).map { it[1] }.filter { it == "DEADLINE" || it == "COVERAGE" } shouldBe emptyList()
        alerts(t1).map { it[1] } shouldBe listOf("SECURITY")
    }

    @Test
    fun `INV-AC-27271 an event of the look-alike type com dynokore gst access AccessAlertRaised v2 goes to the dead letter at once`(
        @TempDir dir: Path,
    ) = run(dir) {
        log.add(event(t1, "ev-6", type = "com.dynokore.gst.access.AccessAlertRaised.v2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        alerts(t1) shouldBe emptyList()
        deadLetters().size shouldBe 1
    }

    @Test
    fun `INV-AC-27271 the accessAlertConsumer bean of the context consumes the first event`(
        @TempDir dir: Path,
    ) {
        val log = Log()
        NotificationContext
            .online(dir)
            .withBean(EventLogReader::class.java, { log }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean(RoleHolders::class.java, { holders }, BeanDefinitionCustomizer { it.isPrimary = true })
            .run { context ->
                val boundary = Boundary(context, log)
                val bean = context.getBean("accessAlertConsumer", JdbcInboxConsumer::class.java)
                // an event id of its own: the inbox row of the shared consumer name is not cleaned up
                log.add(event(t1, "ev-bean-" + UUID.randomUUID()))
                try {
                    bean.pollOnce() shouldBe
                        PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
                    boundary.alerts(t1) shouldBe listOf(raised)
                    boundary.recipients(t1) shouldBe listOf("u1", "u2")
                    boundary.tx(t1) {
                        boundary.dsl.fetchCount(INBOX, INBOX.CONSUMER.eq(AccessAlertConsumerConfiguration.CONSUMER))
                    } shouldBe 1
                } finally {
                    NotificationDb.superuser().use {
                        it.createStatement().use { s ->
                            s.execute(
                                "delete from notification.consumer_offset " +
                                    "where consumer = '${AccessAlertConsumerConfiguration.CONSUMER}' " +
                                    "and topic = '${AccessAlertConsumerConfiguration.TOPIC}'",
                            )
                        }
                    }
                }
            }
    }
}
