package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.packs.JooqMdePackReplica
import com.dynokore.gst.payload.adapters.packs.JooqRulePackReplica
import com.dynokore.gst.payload.adapters.packs.RulePackEventsHandler
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
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
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-27183 through the real inbox consumers on the tables of schema `payload` over PostgreSQL (P109). Only the
 * event-log read is a list in memory.
 */
class RulePackReplicaConsumerDbTest {
    private val actor = "svc:payload-svc"
    private val json = EnvelopeJson()
    private val schematron = "schematron"
    private val severity = "severity-override"

    private class Log : EventLogReader {
        private val records = mutableMapOf<String, MutableList<LogRecord>>()

        fun add(
            topic: String,
            envelopeJson: String,
        ) {
            val list = records.getOrPut(topic) { mutableListOf() }
            list += LogRecord(list.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records[topic].orEmpty().filter { it.offset > afterOffset }.take(limit)
    }

    private inner class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val replica: JooqMdePackReplica = context.getBean(JooqMdePackReplica::class.java)
        val rules: JooqRulePackReplica = context.getBean(JooqRulePackReplica::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val log = Log()
        private val consumerName = PackReplicaConfiguration.CONSUMER + "-" + UUID.randomUUID()

        private fun consumer(
            type: String,
            topic: String,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(consumerName, topic, PackReplicaConfiguration.SCHEMA),
            catalogue,
            log,
            scope,
            dsl,
            RulePackEventsHandler(type, replica, rules),
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )

        fun deliver(
            tenant: TenantId,
            staged: Boolean,
            data: String,
        ): PollResult {
            val type = if (staged) RulePackEventsHandler.STAGED_TYPE else RulePackEventsHandler.ACTIVATED_TYPE
            val topic = if (staged) RulePackEventsHandler.STAGED_TOPIC else RulePackEventsHandler.ACTIVATED_TOPIC
            log.add(topic, json.write(envelope(tenant, type, data)))
            return consumer(type, topic).pollOnce()
        }

        fun <T> tx(
            tenant: TenantId,
            body: () -> T,
        ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }
    }

    private fun envelope(
        tenant: TenantId,
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/rules-svc",
        type = type,
        subject = "P-SCH",
        time = Instant.parse("2026-11-09T08:00:00Z"),
        tenant = tenant,
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun staged(
        version: String,
        contents: String,
        type: String = "schematron",
        id: String = "P-SCH",
    ) = """{"packIdentifier":"$id","packType":"$type","version":"$version","effectiveFrom":"2026-12-01",""" +
        """"minEngineVersion":"1.0.0","artefact":{"manifest":{},"checksums":{},"signature":"s","contents":$contents}}"""

    private fun activated(
        version: String,
        id: String = "P-SCH",
    ) = """{"packIdentifier":"$id","version":"$version","effectiveFrom":"2026-12-01"}"""

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        PayloadContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun count(
        sql: String,
        tenant: TenantId,
    ): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, tenant.value)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun ruleRows(tenant: TenantId) =
        count("select count(*) from payload.rule_pack_replica where tenant_id = ?", tenant)

    private fun ruleActive(tenant: TenantId) =
        count("select count(*) from payload.rule_pack_replica where tenant_id = ? and active", tenant)

    private fun mdeRows(tenant: TenantId) =
        count("select count(*) from payload.mde_pack_replica where tenant_id = ?", tenant)

    private val applied = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
    private val ignored = PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
    private val deadLettered = PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)

    @Test
    fun `INV-AC-27183 a staged and activated schematron pack is held byte for byte`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"version":"1","files":[{"path":"a.sch","content":"x"}]}""")) shouldBe
            applied
        tx(t1) { rules.active(schematron) } shouldBe null
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        val active = tx(t1) { rules.active(schematron) }
        active?.packIdentifier shouldBe "P-SCH"
        active?.version shouldBe "1.0.0"
        active?.contentsJson shouldBe """{"files": [{"path": "a.sch", "content": "x"}], "version": "1"}"""
        tx(t1) { rules.contentsOf(schematron, "P-SCH", "1.0.0") } shouldBe active?.contentsJson
    }

    @Test
    fun `INV-AC-27183 a staged and activated severity-override pack is held`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val id = "P-SEV"
        deliver(t1, true, staged("1.0.0", """{"escalations":[{"rule":"R1"}]}""", severity, id)) shouldBe applied
        deliver(t1, false, activated("1.0.0", id)) shouldBe applied
        val active = tx(t1) { rules.active(severity) }
        active?.packIdentifier shouldBe id
        active?.contentsJson shouldBe """{"escalations": [{"rule": "R1"}]}"""
        tx(t1) { rules.active(schematron) } shouldBe null
        ruleActive(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-27183 a redelivery of a staged schematron version changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val first = staged("1.0.0", """{"version":"1","files":[]}""")
        deliver(t1, true, first) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        deliver(t1, true, first) shouldBe ignored
        deliver(t1, false, activated("1.0.0")) shouldBe ignored
        ruleRows(t1) shouldBe 1
        ruleActive(t1) shouldBe 1
        tx(t1) { rules.active(schematron) }?.version shouldBe "1.0.0"
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-27183 a staged schematron version with other contents is a contract violation and the stored one is kept`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, true, staged("1.0.0", """{"a":2}""")) shouldBe deadLettered
        tx(t1) { rules.contentsOf(schematron, "P-SCH", "1.0.0") } shouldBe """{"a": 1}"""
        ruleRows(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-27183 activating a second schematron version leaves exactly one active`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, true, staged("2.0.0", """{"a":2}""")) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        deliver(t1, false, activated("2.0.0")) shouldBe applied
        tx(t1) { rules.active(schematron) }?.version shouldBe "2.0.0"
        ruleRows(t1) shouldBe 2
        ruleActive(t1) shouldBe 1
    }

    @Test
    fun `INV-AC-27183 activating a schematron version leaves the severity-override active version unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"escalations":[{"r":1}]}""", severity, "P-SEV")) shouldBe applied
        deliver(t1, false, activated("1.0.0", "P-SEV")) shouldBe applied
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        tx(t1) { rules.active(severity) }?.packIdentifier shouldBe "P-SEV"
        tx(t1) { rules.active(schematron) }?.packIdentifier shouldBe "P-SCH"
        ruleActive(t1) shouldBe 2
        deliver(t1, true, staged("2.0.0", """{"a":2}""")) shouldBe applied
        deliver(t1, false, activated("2.0.0")) shouldBe applied
        tx(t1) { rules.active(severity) }?.version shouldBe "1.0.0"
        ruleActive(t1) shouldBe 2
    }

    @Test
    fun `INV-AC-27183 an activation before staging is retried`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        shouldThrow<IllegalStateException> { deliver(t1, false, activated("9.9.9")) }
        shouldThrow<IllegalStateException> { tx(t1) { rules.activate(schematron, "P-SCH", "9.9.9") } }
        ruleRows(t1) shouldBe 1
        ruleActive(t1) shouldBe 0
    }

    @Test
    fun `INV-AC-27183 another tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val t2 = TenantId(UUID.randomUUID())
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        tx(t1) { rules.active(schematron) }?.version shouldBe "1.0.0"
        tx(t2) { rules.active(schematron) } shouldBe null
        tx(t2) { rules.contentsOf(schematron, "P-SCH", "1.0.0") } shouldBe null
        shouldThrow<IllegalStateException> { tx(t2) { rules.activate(schematron, "P-SCH", "1.0.0") } }
        // the tenant predicate of the statement holds on its own: a session bound to t1 reads nothing for t2
        val asT2 = JooqRulePackReplica(dsl) { t2.value }
        tx(t1) { asT2.active(schematron) } shouldBe null
        tx(t1) { asT2.contentsOf(schematron, "P-SCH", "1.0.0") } shouldBe null
    }

    @Test
    fun `INV-AC-27183 a pack of another type is held in no replica`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"s":1}""", "Scope", "P-SCOPE")) shouldBe ignored
        deliver(t1, true, staged("1.0.0", """{"s":1}""", "Schematron", "P-CASE")) shouldBe ignored
        deliver(t1, false, activated("1.0.0", "P-SCOPE")) shouldBe ignored
        ruleRows(t1) shouldBe 0
        mdeRows(t1) shouldBe 0
        tx(t1) { rules.active(schematron) } shouldBe null
        tx(t1) { rules.active(severity) } shouldBe null
    }

    @Test
    fun `INV-AC-27183 an MDE pack is still held only in the MDE replica`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", """{"m":1}""", "MDE", "P-MDE")) shouldBe applied
        deliver(t1, false, activated("1.0.0", "P-MDE")) shouldBe applied
        mdeRows(t1) shouldBe 1
        ruleRows(t1) shouldBe 0
        tx(t1) { replica.active() }?.packIdentifier shouldBe "P-MDE"
        deliver(t1, true, staged("1.0.0", """{"a":1}""")) shouldBe applied
        deliver(t1, false, activated("1.0.0")) shouldBe applied
        tx(t1) { replica.active() }?.packIdentifier shouldBe "P-MDE"
        mdeRows(t1) shouldBe 1
    }
}
