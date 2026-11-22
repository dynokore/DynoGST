package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.SignedBindings
import com.dynokore.gst.payload.application.build.DecisionInputFact
import com.dynokore.gst.payload.application.build.DecisionPackVersion
import com.dynokore.gst.payload.application.build.PayloadRejectedFacts
import com.dynokore.gst.payload.application.build.RejectionDecision
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * INV-AC-27171: the staged `PayloadRejected` row against the real database as `payload_runtime` with a signed binding.
 * The row `data` is compared to the whole expected JSON by equality.
 */
class PayloadRejectedDecisionsDbTest {
    private val mapper = ObjectMapper()
    private val tenant = UUID.randomUUID()

    private val catalogue =
        EventCatalogue(
            listOf(
                CatalogueEntry(
                    OutboxPayloadRejectedPublisher.EVENT_TYPE,
                    "gst.payload.payload-rejected.v1",
                    setOf("payload-svc"),
                    contentTopic = false,
                    retainedWhileResolvable = false,
                ),
            ),
        )

    private val tenants =
        object : TenantContext {
            override fun current() = TenantId(tenant)

            override fun currentActor(): String? = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun stage(facts: PayloadRejectedFacts) {
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            val writer =
                JdbcOutboxWriter(
                    DSL.using(c, SQLDialect.POSTGRES),
                    "payload",
                    "payload-svc",
                    "test-1",
                    catalogue,
                    Clock.systemUTC(),
                )
            OutboxPayloadRejectedPublisher(writer, tenants).stage(facts)
            c.commit()
        }
    }

    /** The one row of [doc]: its `docsequence` column and its `data`. */
    private fun row(doc: UUID): Pair<Long, JsonNode> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select docsequence, data::text from payload.outbox " +
                        "where tenant_id = ? and doc_uuid = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant)
                    p.setObject(2, doc)
                    p.setString(3, OutboxPayloadRejectedPublisher.EVENT_TYPE)
                    p.executeQuery().use { r ->
                        check(r.next()) { "no PayloadRejected row for $doc" }
                        (r.getLong(1) to mapper.readTree(r.getString(2))).also { check(!r.next()) { "two rows" } }
                    }
                }
        }

    private val trace = "{\"finding\":\"BLOCK\"}"

    private fun facts(
        doc: UUID,
        decisions: List<RejectionDecision>?,
    ) = PayloadRejectedFacts("E-001", doc, 1, null, trace, null, decisions)

    private fun entry(
        assertion: String? = "Total must equal the sum of lines",
        input: DecisionInputFact = DecisionInputFact("lineTotal", "12.50", "BT-131"),
        code: String = "PAY-002",
    ) = RejectionDecision(
        code,
        "PINT-SG-R001",
        listOf(DecisionPackVersion("SCHEMATRON", "SCH-3")),
        listOf(input),
        assertion,
    )

    private fun outboxRows(doc: UUID): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("select count(*) from payload.outbox where tenant_id = ? and doc_uuid = ?").use { p ->
                p.setObject(1, tenant)
                p.setObject(2, doc)
                p.executeQuery().use { r ->
                    r.next()
                    r.getInt(1)
                }
            }
        }

    private fun expected(
        doc: UUID,
        sequence: Long,
        decisions: String?,
    ): JsonNode =
        mapper.readTree(
            """{"docUuid":"$doc","version":1,"docsequence":$sequence,"validationTrace":{"finding":"BLOCK"}""" +
                decisions.orEmpty().let { if (it.isEmpty()) it else ""","decisions":$it""" } + "}",
        )

    @Test
    fun `INV-AC-27171 a fact with one decision entry stages decisions equal to the entry`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, listOf(entry())))
        val (sequence, data) = row(doc)
        data shouldBe
            expected(
                doc,
                sequence,
                """[{"code":"PAY-002","ruleId":"PINT-SG-R001",
                    "packVersions":[{"packIdentifier":"SCHEMATRON","version":"SCH-3"}],
                    "inputs":[{"name":"lineTotal","value":"12.50","businessTermId":"BT-131"}],
                    "assertionText":"Total must equal the sum of lines"}]""",
            )
    }

    @Test
    fun `INV-AC-27171 a fact with no decisions stages no decisions member`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, null))
        val (sequence, data) = row(doc)
        data shouldBe expected(doc, sequence, null)
    }

    @Test
    fun `INV-AC-27171 a fact with an empty decisions list stages no decisions member`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, emptyList()))
        val (sequence, data) = row(doc)
        data shouldBe expected(doc, sequence, null)
    }

    @Test
    fun `INV-AC-27171 a decision entry without assertionText stages none`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, listOf(entry(assertion = null, input = DecisionInputFact("lineTotal", "12.50")))))
        val (sequence, data) = row(doc)
        data shouldBe
            expected(
                doc,
                sequence,
                """[{"code":"PAY-002","ruleId":"PINT-SG-R001",
                    "packVersions":[{"packIdentifier":"SCHEMATRON","version":"SCH-3"}],
                    "inputs":[{"name":"lineTotal","value":"12.50"}]}]""",
            )
    }

    @Test
    fun `INV-AC-27171 two decision entries are staged in the given order`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, listOf(entry(code = "PAY-002"), entry(code = "PAY-001"))))
        val (_, data) = row(doc)
        (0..1).map { data["decisions"][it]["code"].stringValue() } shouldBe listOf("PAY-002", "PAY-001")
    }

    @Test
    fun `INV-AC-27171 an assertionText of 501 characters is refused before anything is staged`() {
        val doc = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> { stage(facts(doc, listOf(entry(assertion = "x".repeat(501))))) }
        outboxRows(doc) shouldBe 0
    }

    @Test
    fun `INV-AC-27171 an assertionText of 500 characters is staged unchanged`() {
        val doc = UUID.randomUUID()
        val text = "x".repeat(500)
        stage(facts(doc, listOf(entry(assertion = text))))
        val (_, data) = row(doc)
        data["decisions"][0]["assertionText"].stringValue() shouldBe text
    }

    @Test
    fun `INV-AC-27171 an entry with an empty packVersions list is refused`() {
        shouldThrow<IllegalArgumentException> {
            RejectionDecision("PAY-002", "PINT-SG-R001", emptyList(), emptyList())
        }
    }
}
