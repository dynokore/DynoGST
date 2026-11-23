package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.packs.JooqMdePackReplica
import com.dynokore.gst.payload.adapters.packs.JooqRulePackReplica
import com.dynokore.gst.payload.adapters.packs.RulePackEventsHandler
import com.dynokore.gst.payload.adapters.packs.SchematronActivation
import com.dynokore.gst.payload.adapters.schematron.CompiledPackCache
import com.dynokore.gst.payload.adapters.schematron.StagedSchematronSource
import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.gst.payload.domain.StagedPackMissingException
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-27230 through the real inbox consumers over PostgreSQL, with the beans of [SchematronConfiguration]. A
 * two-file pack whose files each fail one assert.
 */
class SchematronActivationDbTest {
    private val actor = "svc:payload-svc"
    private val json = EnvelopeJson()
    private val document = "<doc/>".toByteArray()

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
        val cache: CompiledPackCache = context.getBean(CompiledPackCache::class.java)
        val activation: SchematronActivation = context.getBean(SchematronActivation::class.java)
        val schematronRunner: SchematronRunner = context.getBean(SchematronRunner::class.java)
        val compiler: SchematronCompiler = context.getBean(SchematronCompiler::class.java)
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
            RulePackEventsHandler(type, replica, rules, activation),
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

        /** Polls the activated topic again without a new item: the retry of the item that did not commit. */
        fun repoll() = consumer(RulePackEventsHandler.ACTIVATED_TYPE, RulePackEventsHandler.ACTIVATED_TOPIC).pollOnce()

        fun <T> tx(
            tenant: TenantId,
            body: () -> T,
        ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

        /** A cache with no held version, reading the replica on a miss exactly as the configuration does. */
        fun newCache() =
            CompiledPackCache(
                schematronRunner,
                StagedSchematronSource { rules.contentsOfVersion("schematron", it) },
                compiler,
            )

        fun ids(
            tenant: TenantId,
            cache: CompiledPackCache,
            version: String,
        ) = tx(tenant) { cache.validate(version, document) }.failedAsserts.map { it.id }
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

    private fun sch(
        id: String,
        attribute: String,
    ) = """<?xml version="1.0" encoding="UTF-8"?>
<sch:schema xmlns:sch="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
  <sch:pattern><sch:rule context="/doc"><sch:assert id="$id" test="@$attribute">$attribute is required.</sch:assert></sch:rule></sch:pattern>
</sch:schema>"""

    private fun file(
        name: String,
        content: String,
    ): String {
        val bytes = content.toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return """{"name":"$name","sha256":"$hash","content":"${Base64.getEncoder().encodeToString(bytes)}"}"""
    }

    // b.sch is listed first: the order of the verdict is the order of the names, never the order of the listing
    private val twoFiles = """{"files":[${file("b.sch", sch("B-01", "b"))},${file("a.sch", sch("A-01", "a"))}]}"""
    private val brokenFile = """{"files":[${file("a.sch", "<sch:schema")}]}"""

    private fun staged(
        version: String,
        contents: String,
        id: String = "P-SCH",
    ) = """{"packIdentifier":"$id","packType":"schematron","version":"$version","effectiveFrom":"2026-12-01",""" +
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

    private fun activeVersion(
        b: Boundary,
        t: TenantId,
    ) = b.tx(t) { b.rules.active("schematron") }?.version

    @Test
    fun `INV-AC-27230 activation compiles the files and validation reports both failed asserts in file-name order`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, false, activated("1.0.0"))
        // the cache of the context holds the set from the activation: no replica read is needed
        cache.validate("1.0.0", document).failedAsserts.map { it.id } shouldBe listOf("A-01", "B-01")
    }

    @Test
    fun `INV-AC-27230 a new cache instance reads the replica on a miss`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, false, activated("1.0.0"))
        ids(t1, newCache(), "1.0.0") shouldBe listOf("A-01", "B-01")
    }

    @Test
    fun `INV-AC-27230 a staged version that was never activated is read on a miss`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, false, activated("1.0.0"))
        deliver(t1, true, staged("2.0.0", twoFiles))
        activeVersion(this, t1) shouldBe "1.0.0"
        ids(t1, newCache(), "2.0.0") shouldBe listOf("A-01", "B-01")
    }

    @Test
    fun `INV-AC-27230 a compile failure on activation leaves the earlier active version and the event is retried`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, false, activated("1.0.0"))
        deliver(t1, true, staged("2.0.0", brokenFile))
        shouldThrow<RuntimeException> { deliver(t1, false, activated("2.0.0")) }
        activeVersion(this, t1) shouldBe "1.0.0"
        cache.validate("1.0.0", document).failedAsserts.map { it.id } shouldBe listOf("A-01", "B-01")
        // the item was not committed: the next poll delivers it again and it fails again
        shouldThrow<RuntimeException> { repoll() }
        activeVersion(this, t1) shouldBe "1.0.0"
    }

    @Test
    fun `INV-AC-27230 two identifiers holding one version raise IllegalStateException`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, true, staged("1.0.0", twoFiles, "P-OTHER"))
        shouldThrow<IllegalStateException> { tx(t1) { rules.contentsOfVersion("schematron", "1.0.0") } }
        shouldThrow<IllegalStateException> { ids(t1, newCache(), "1.0.0") }
    }

    @Test
    fun `INV-AC-27230 another tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val t2 = TenantId(UUID.randomUUID())
        deliver(t1, true, staged("1.0.0", twoFiles))
        deliver(t1, false, activated("1.0.0"))
        tx(t1) { rules.contentsOfVersion("schematron", "1.0.0") } shouldBe tx(t1) { rules.active("schematron") }?.contentsJson
        tx(t2) { rules.contentsOfVersion("schematron", "1.0.0") } shouldBe null
        shouldThrow<StagedPackMissingException> { ids(t2, newCache(), "1.0.0") }
        // the tenant predicate of the statement holds on its own: a session bound to t1 reads nothing for t2
        val asT2 = JooqRulePackReplica(dsl) { t2.value }
        tx(t1) { asT2.contentsOfVersion("schematron", "1.0.0") } shouldBe null
    }
}
