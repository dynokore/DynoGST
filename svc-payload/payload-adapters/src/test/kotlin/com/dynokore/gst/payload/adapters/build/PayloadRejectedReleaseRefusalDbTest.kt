package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.SignedBindings
import com.dynokore.gst.payload.application.build.NoAcceptedReleaseFacts
import com.dynokore.gst.payload.application.build.PackVersionFact
import com.dynokore.gst.payload.application.build.PayloadRejectedFacts
import com.dynokore.gst.payload.application.build.ReleaseRefusalReason
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-26578: the staged `PayloadRejected` row of a release refusal against the real database as `payload_runtime`
 * with a signed binding. The row `data` is compared to the whole expected JSON by equality. SYNTH-* is synthetic.
 */
class PayloadRejectedReleaseRefusalDbTest {
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

    /** Commits even when the publisher throws, so a row appended before the refusal would stay and be counted. */
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
            try {
                OutboxPayloadRejectedPublisher(writer, tenants).stage(facts)
            } finally {
                c.commit()
            }
        }
    }

    private fun rows(doc: UUID): List<Pair<Long, JsonNode>> =
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
                        buildList { while (r.next()) add(r.getLong(1) to mapper.readTree(r.getString(2))) }
                    }
                }
        }

    private val full =
        NoAcceptedReleaseFacts(
            reason = ReleaseRefusalReason.NO_ACCEPTED_RELEASE,
            family = "PINT_SG",
            transactionDate = LocalDate.of(2026, 3, 20),
            submissionDate = LocalDate.of(2026, 4, 1),
            notOlderThan = "SYNTH-B",
            acceptedOnDate = listOf("SYNTH-A", "SYNTH-0"),
        )

    private val fullTrace =
        """{"code":"PAY-020","reason":"NO_ACCEPTED_RELEASE","family":"PINT_SG","transactionDate":"2026-03-20",
            "submissionDate":"2026-04-01","notOlderThan":"SYNTH-B","acceptedOnDate":["SYNTH-A","SYNTH-0"]}"""

    private fun facts(
        doc: UUID,
        refusal: NoAcceptedReleaseFacts = full,
        code: String? = "PAY-020",
        trace: String? = null,
        packs: List<PackVersionFact>? = null,
    ) = PayloadRejectedFacts("E-001", doc, 2, code, trace, packs, releaseRefusal = refusal)

    private fun expected(
        doc: UUID,
        sequence: Long,
        trace: String,
        extra: String = "",
    ): JsonNode =
        mapper.readTree(
            """{"docUuid":"$doc","version":2,"docsequence":$sequence,"validationTrace":$trace,
                "code":"PAY-020"$extra}""",
        )

    @Test
    fun `INV-AC-26578 staged refusal carries the code and every trace member`() {
        val doc = UUID.randomUUID()
        stage(facts(doc))
        val (sequence, data) = rows(doc).single()
        data shouldBe expected(doc, sequence, fullTrace)
    }

    @Test
    fun `INV-AC-26578 staged refusal after a pack read carries packVersions`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, packs = listOf(PackVersionFact("MDE", "MDE-7"))))
        val (sequence, data) = rows(doc).single()
        data shouldBe
            expected(doc, sequence, fullTrace, ""","packVersions":[{"packType":"MDE","version":"MDE-7"}]""")
    }

    @Test
    fun `INV-AC-26578 refusal without optional facts omits them and keeps an empty acceptedOnDate`() {
        val doc = UUID.randomUUID()
        val sparse =
            full.copy(
                reason = ReleaseRefusalReason.NO_TRANSACTION_DATE,
                transactionDate = null,
                notOlderThan = null,
                acceptedOnDate = emptyList(),
            )
        stage(facts(doc, sparse))
        val (sequence, data) = rows(doc).single()
        data shouldBe
            expected(
                doc,
                sequence,
                """{"code":"PAY-020","reason":"NO_TRANSACTION_DATE","family":"PINT_SG",
                    "submissionDate":"2026-04-01","acceptedOnDate":[]}""",
            )
    }

    @Test
    fun `INV-AC-26578 refusal with another code or a second trace throws and leaves no row`() {
        val codes = listOf("PAY-02O", "pay-020", "PAY-0200", "PAY-02", "PAY-020 ", " PAY-020", "PAY-010", "", null)
        val traces = listOf("{\"finding\":\"BLOCK\"}", "text", "{}", "")
        val cases =
            codes.map { code -> "code '$code'" to { doc: UUID -> facts(doc, code = code) } } +
                traces.map { trace -> "trace '$trace'" to { doc: UUID -> facts(doc, trace = trace) } }
        cases.forEach { (case, of) ->
            withClue(case) {
                val doc = UUID.randomUUID()
                shouldThrow<IllegalArgumentException> { stage(of(doc)) }
                rows(doc).size shouldBe 0
            }
        }
    }
}
