package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.FeedHealthState.Companion.FEED_HEALTH_STATE
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Inbox.Companion.INBOX
import com.dynokore.gst.notification.application.alerting.FeedHealthNotDelivered
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
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.DisplayName
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
 * INV-AC-1944 through the real inbox consumer (`JdbcInboxConsumer`) with the handler, the use case and the jOOQ
 * adapters of the TPL-18 context over PostgreSQL (P109). Only the event-log read is a list in memory: the `eventlog`
 * schema belongs to the event-log instance.
 */
class FeedHealthConsumerBoundaryTest {
    private val actor = "svc:notification-svc"
    private val at = Instant.parse("2026-11-12T10:00:00Z")
    private val json = EnvelopeJson()
    private var sequence = 0L

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
        topic: String,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val replica: AuthorityReplica = context.getBean(AuthorityReplica::class.java)
        var grants = 0L

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        val consumerName = FeedHealthConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, topic, FeedHealthConsumerConfiguration.SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("feedHealthChangedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        topic: String = FeedHealthConsumerConfiguration.TOPIC,
        block: Boundary.(TenantId) -> Unit,
    ) {
        NotificationContext.online(dir).run { context -> Boundary(context, topic).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    @Suppress("LongParameterList")
    private fun changed(
        tenant: TenantId,
        eventId: String,
        state: String = "FAILED",
        control: String = "SEQUENCE",
        version: Int? = 1,
        type: String = "com.dynokore.gst.ingestion.FeedHealthChanged.v1",
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/ingestion-svc",
                type = type,
                subject = "E-1",
                time = at,
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "ingestion-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"feed":"IRAS","entityRef":"E-1","period":"2026-Q1","control":"$control","state":"$state"""" +
                        version?.let { ""","stateVersion":$it""" }.orEmpty() + "}",
            ),
        )

    private fun Boundary.holder(
        tenant: TenantId,
        user: String,
        role: String,
    ) {
        grants += 1
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                grants,
                AuthorityRecord.RoleGrantRecord(
                    "g-$user-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.TENANT, tenant.toString(), at.minusSeconds(60)),
                ),
            ),
        )
    }

    private fun Boundary.recipients(tenant: TenantId): List<List<String>> =
        tx(tenant) {
            dsl
                .select(FEED_HEALTH_STATE.STATE, FEED_HEALTH_STATE.RECIPIENTS)
                .from(FEED_HEALTH_STATE)
                .fetch()
                .map { listOf(it.value1()!!) + it.value2()!!.filterNotNull() }
        }

    private fun Boundary.alerts(
        tenant: TenantId,
        entity: String? = null,
    ): List<List<String?>> =
        tx(tenant) {
            dsl
                .selectFrom(ALERT)
                .fetch()
                .filter { entity == null || it.entityRef == entity }
                .map { listOf(it.state, it.alertClass, it.trigger, it.entityRef, it.periodRef) }
        }

    private fun Boundary.inboxRows(tenant: TenantId) =
        tx(tenant) { dsl.fetchCount(INBOX, INBOX.CONSUMER.eq(consumerName)) }

    private val raised = listOf("RAISED", "COVERAGE", "SEQUENCE", "E-1", "2026-Q1")

    @Test
    @DisplayName(
        "INV-AC-1944 a FAILED feed health event of a new tenant raises one COVERAGE alert " +
            "and records the integration_engineer holders as recipients",
    )
    fun feedHealthCase1(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        holder(tenant, "u-eng-b", "integration.engineer")
        holder(tenant, "u-eng-a", "integration.engineer")
        holder(tenant, "u-admin", "tenant.admin")
        holder(TenantId(UUID.randomUUID()), "u-other", "integration.engineer")
        log.add(changed(tenant, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised)
        recipients(tenant) shouldBe listOf(listOf("FAILED", "u-eng-a", "u-eng-b"))
        inboxRows(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-1944 a tenant with no integration_engineer falls back to tenant_admin`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        holder(tenant, "u-admin", "tenant.admin")
        log.add(changed(tenant, "ev-1", control = "WATERMARK"))
        consumer.pollOnce().applied shouldBe 1
        alerts(tenant) shouldBe listOf(listOf("RAISED", "COVERAGE", "WATERMARK", "E-1", "2026-Q1"))
        recipients(tenant) shouldBe listOf(listOf("FAILED", "u-admin"))
    }

    @Test
    fun `INV-AC-1944 a tenant with neither leaves the event unprocessed for retry and writes no alert row`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(changed(tenant, "ev-1"))
        shouldThrow<FeedHealthNotDelivered> { consumer.pollOnce() }
        alerts(tenant) shouldBe emptyList()
        recipients(tenant) shouldBe emptyList()
        inboxRows(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-1944 a CLEARED event resolves the alert`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        holder(tenant, "u-eng", "integration.engineer")
        log.add(changed(tenant, "ev-1", version = 1))
        log.add(changed(tenant, "ev-2", state = "CLEARED", version = 2))
        consumer.pollOnce().applied shouldBe 2
        alerts(tenant) shouldBe listOf(listOf("RESOLVED", "COVERAGE", "SEQUENCE", "E-1", "2026-Q1"))
    }

    @Test
    @DisplayName(
        "INV-AC-1944 the event of the same type with another version suffix goes to the dead letter " +
            "and writes no alert row",
    )
    fun feedHealthCase2(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        holder(tenant, "u-eng", "integration.engineer")
        log.add(changed(tenant, "ev-1", type = "com.dynokore.gst.ingestion.FeedHealthChanged.v2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe emptyList()
    }
}
