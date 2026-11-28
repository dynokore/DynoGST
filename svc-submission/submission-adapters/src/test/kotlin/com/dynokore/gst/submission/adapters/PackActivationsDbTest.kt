package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.MappingPackActivatedHandler
import com.dynokore.gst.submission.adapters.persistence.JooqPackActivations
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The pack activation history as `submission_runtime` (INV-AC-26739): the handler driven by the library
 * `JdbcInboxConsumer` over a fixed list of records, and the table's shape, grants and tenant isolation.
 */
class PackActivationsDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private var bound: UUID? = null
    private var open = false

    private val tenants =
        object : TenantContext, PlatformTenantBinding {
            override fun <T> bindPlatform(
                actor: String,
                block: () -> T,
            ): T = bind(TenantId.PLATFORM, actor, block)

            override fun current(): TenantId = TenantId(checkNotNull(bound))

            override fun currentActor(): String = SignedBindings.ACTOR

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T {
                val outer = bound
                bound = tenant.value
                return try {
                    block()
                } finally {
                    bound = outer
                }
            }
        }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                if (open) return block()
                open = true
                return try {
                    bound?.let { SignedBindings.bind(connection, it) }
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                } finally {
                    open = false
                }
            }
        }

    private val handler = MappingPackActivatedHandler(tenants, JooqPackActivations(dsl))

    private val records = mutableListOf<LogRecord>()

    private val consumer =
        JdbcInboxConsumer(
            ConsumerSubscription("t26745-" + UUID.randomUUID(), MappingPackActivatedHandler.TOPIC, "submission"),
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        MappingPackActivatedHandler.EVENT_TYPE,
                        MappingPackActivatedHandler.TOPIC,
                        setOf("rules-svc"),
                    ),
                ),
            ),
            object : EventLogReader {
                override fun read(
                    topic: String,
                    afterOffset: Long,
                    limit: Int,
                ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
            },
            TenantScope(
                tenants,
                runner,
                object : CurrentConnection {
                    override fun require(): Connection = connection
                },
                SignedBindings.ACTOR,
            ),
            dsl,
            handler,
            RebuildRequiredSink { },
        )

    @AfterEach
    fun close() = connection.close()

    private fun data(
        label: String,
        from: String,
        to: String?,
        by: String,
        at: String,
    ): String {
        val end = to?.let { ""","effectiveTo":"$it"""" }.orEmpty()
        return """{"entityRef":"E-001","packId":"MP-$label","packVersion":"$label",""" +
            """"effectiveFrom":"$from"$end,"activatedBy":"$by","activatedAt":"$at"}"""
    }

    private fun event(
        tenant: UUID,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/rules-svc",
        type = MappingPackActivatedHandler.EVENT_TYPE,
        subject = "MP",
        time = Instant.parse("2026-11-01T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun deliver(event: Envelope): PollResult {
        records += LogRecord(records.size + 1L, EnvelopeJson().write(event))
        return consumer.pollOnce()
    }

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR) { runner.inTransaction(block) }

    private fun rows(tenant: UUID): List<List<Any?>> =
        asTenant(tenant) {
            dsl.fetch(SELECT_ROWS).map {
                listOf(
                    it.get(0),
                    it.get(1),
                    it.get(2),
                    it.get(3),
                    it.get(4, LocalDate::class.java),
                    it.get(5, LocalDate::class.java),
                    it.get(6),
                    it.get(7, OffsetDateTime::class.java).toInstant(),
                )
            }
        }

    private fun deadLetters(tenant: UUID): Int =
        asTenant(tenant) {
            dsl.fetch("select raw from submission.event_dead_letter where tenant_id = ?", tenant).size
        }

    private fun row(
        event: Envelope,
        label: String,
        from: String,
        to: String?,
        by: String,
        at: String,
    ) = listOf(
        event.id.value,
        "E-001",
        "MP-$label",
        label,
        LocalDate.parse(from),
        to?.let(LocalDate::parse),
        by,
        Instant.parse(at),
    )

    @Test
    fun `INV-AC-26739 two activations of one entity, one delivered twice, are stored once each`() {
        val first = event(tenantA, data("v1", "2026-01-01", "2026-10-31", "user-1", "2025-12-20T01:02:03Z"))
        val second = event(tenantA, data("v2", "2026-11-01", null, "user-2", "2026-10-30T09:15:00Z"))
        deliver(first).applied shouldBe 1
        deliver(second).applied shouldBe 1
        deliver(second).duplicates shouldBe 1
        rows(tenantA) shouldBe
            listOf(
                row(first, "v1", "2026-01-01", "2026-10-31", "user-1", "2025-12-20T01:02:03Z"),
                row(second, "v2", "2026-11-01", null, "user-2", "2026-10-30T09:15:00Z"),
            )
    }

    @Test
    fun `INV-AC-26739 a repeat that passes the inbox inserts nothing and changes nothing`() {
        val first = event(tenantA, data("v1", "2026-01-01", null, "user-1", "2025-12-20T01:02:03Z"))
        asTenant(tenantA) { handler.handle(first) }
        val altered = first.copy(dataJson = data("v9", "2027-01-01", null, "user-9", "2026-12-20T01:02:03Z"))
        asTenant(tenantA) { handler.handle(altered) }
        rows(tenantA) shouldBe listOf(row(first, "v1", "2026-01-01", null, "user-1", "2025-12-20T01:02:03Z"))
    }

    @Test
    fun `INV-AC-26739 a malformed event is a poison record and stores nothing`() {
        val bad = data("v1", "not-a-date", null, "user-1", "2025-12-20T01:02:03Z")
        for (payload in listOf(bad, """{"entityRef":"E-001"}""")) {
            val result = deliver(event(tenantA, payload))
            result.deadLettered shouldBe 1
            result.applied shouldBe 0
        }
        rows(tenantA) shouldBe emptyList()
        deadLetters(tenantA) shouldBe 2
        // the poison records do not hold the topic: the next good record is applied
        deliver(event(tenantA, data("v2", "2026-11-01", null, "user-2", "2026-10-30T09:15:00Z"))).applied shouldBe 1
        rows(tenantA).size shouldBe 1
    }

    @Test
    fun `INV-AC-26739 an event whose tenant differs from the transaction tenant stores nothing`() {
        val foreign = event(tenantB, data("v1", "2026-01-01", null, "user-1", "2025-12-20T01:02:03Z"))
        shouldThrow<ContractViolation> { asTenant(tenantA) { handler.handle(foreign) } }
        rows(tenantA) shouldBe emptyList()
        rows(tenantB) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26739 another tenant reads no row`() {
        deliver(event(tenantA, data("v1", "2026-01-01", null, "user-1", "2025-12-20T01:02:03Z"))).applied shouldBe 1
        rows(tenantB) shouldBe emptyList()
        rows(tenantA).size shouldBe 1
    }

    @Test
    fun `INV-AC-26739 the table has the named columns and the unique key`() {
        SubmissionDb.superuser().use { c ->
            fun strings(sql: String): List<String> =
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
            strings(
                "select column_name || ' ' || data_type || ' ' || is_nullable from information_schema.columns " +
                    "where table_schema = 'submission' and table_name = 'pack_activation' order by ordinal_position",
            ) shouldBe
                listOf(
                    "tenant_id uuid NO",
                    "event_id text NO",
                    "entity_ref text NO",
                    "pack_id text NO",
                    "pack_label text NO",
                    "effective_from date NO",
                    "effective_to date YES",
                    "activated_by text NO",
                    "activated_at timestamp with time zone NO",
                )
            strings(
                "select pg_get_constraintdef(oid) from pg_constraint " +
                    "where conrelid = 'submission.pack_activation'::regclass and contype = 'u'",
            ) shouldBe listOf("UNIQUE (tenant_id, event_id)")
        }
    }

    @Test
    fun `INV-AC-26739 the runtime role holds select and insert only`() {
        fun has(privilege: String) =
            asTenant(tenantA) {
                dsl.fetchValue(
                    "select has_table_privilege('submission_runtime', 'submission.pack_activation', ?)",
                    privilege,
                )
            }
        has("SELECT") shouldBe true
        has("INSERT") shouldBe true
        for (privilege in listOf("UPDATE", "DELETE", "TRUNCATE")) has(privilege) shouldBe false
        for (sql in listOf(
            "update submission.pack_activation set pack_label = 'x'",
            "delete from submission.pack_activation",
        )) {
            val failure = runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull()
            connection.rollback()
            (failure as SQLException).sqlState shouldBe "42501"
        }
    }

    private companion object {
        const val SELECT_ROWS =
            "select event_id, entity_ref, pack_id, pack_label, effective_from, effective_to, activated_by, " +
                "activated_at from submission.pack_activation order by activated_at"
    }
}
