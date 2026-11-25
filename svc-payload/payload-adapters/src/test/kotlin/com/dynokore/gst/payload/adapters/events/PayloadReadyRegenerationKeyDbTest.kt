package com.dynokore.gst.payload.adapters.events

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.SignedBindings
import com.dynokore.gst.payload.application.build.PackVersionFact
import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.RegenerationFacts
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
import java.util.UUID

/**
 * INV-AC-26578: the staged `PayloadReady` row against the real database as `payload_runtime` with a signed binding.
 * The row `data` is compared to the whole expected JSON by equality. SYNTH-B and MDE-7 are synthetic.
 */
class PayloadReadyRegenerationKeyDbTest {
    private val mapper = ObjectMapper()
    private val tenant = UUID.randomUUID()

    private val catalogue =
        EventCatalogue(
            listOf(
                CatalogueEntry(
                    OutboxPayloadReadyPublisher.EVENT_TYPE,
                    "gst.payload.payload-ready.v1",
                    setOf("payload-svc"),
                    contentTopic = true,
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
    private fun stage(facts: PayloadReadyFacts) {
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
                OutboxPayloadReadyPublisher(writer, tenants).stage(facts)
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
                    p.setString(3, OutboxPayloadReadyPublisher.EVENT_TYPE)
                    p.executeQuery().use { r ->
                        buildList { while (r.next()) add(r.getLong(1) to mapper.readTree(r.getString(2))) }
                    }
                }
        }

    private val mde = PackVersionFact("MDE", "MDE-7")

    private fun regeneration(
        canonicalVersion: Int = 2,
        release: String = "SYNTH-B",
        packs: List<PackVersionFact> = listOf(mde),
    ) = RegenerationFacts(canonicalVersion, release, packs)

    private fun facts(
        doc: UUID,
        regeneration: RegenerationFacts?,
        aggregateId: String? = null,
    ) = PayloadReadyFacts(
        entityRef = "E-001",
        docUuid = doc,
        version = 1,
        submissionType = "1A",
        specificationVersion = "PINT-SG-1",
        payloadBytes = "abc".toByteArray(Charsets.UTF_8),
        payloadSha256 = SHA_ABC,
        aggregateId = aggregateId,
        regeneration = regeneration,
    )

    /** The members main writes, without `aggregateId`; [extra] is appended inside the object. */
    private fun expected(
        doc: UUID,
        sequence: Long,
        extra: String = "",
    ): JsonNode =
        mapper.readTree(
            """{"docUuid":"$doc","version":1,"docsequence":$sequence,"submissionType":"1A",
                "specificationVersion":"PINT-SG-1","payloadBytes":"YWJj","payloadSha256":"$SHA_ABC"$extra}""",
        )

    private val key =
        """"regenerationKey":{"canonicalVersion":2,"specificationVersion":"SYNTH-B",
            "packVersions":[{"packRef":"MDE","version":"MDE-7"}]}"""

    @Test
    fun `INV-AC-26578 staged event carries the regeneration key`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, regeneration()))
        val (sequence, data) = rows(doc).single()
        data shouldBe expected(doc, sequence, ",$key")
    }

    @Test
    fun `INV-AC-26578 staged event without regeneration has no regenerationKey member`() {
        val bare = UUID.randomUUID()
        val aggregated = UUID.randomUUID()
        stage(facts(bare, null))
        stage(facts(aggregated, null, "AGG-2026-0001"))
        rows(bare).single().let { (sequence, data) -> data shouldBe expected(bare, sequence) }
        rows(aggregated).single().let { (sequence, data) ->
            data shouldBe expected(aggregated, sequence, ""","aggregateId":"AGG-2026-0001"""")
        }
    }

    @Test
    fun `INV-AC-26578 staged event carries both aggregateId and the regeneration key`() {
        val doc = UUID.randomUUID()
        stage(facts(doc, regeneration(), "AGG-2026-0001"))
        val (sequence, data) = rows(doc).single()
        data shouldBe expected(doc, sequence, ""","aggregateId":"AGG-2026-0001",$key""")
    }

    @Test
    fun `INV-AC-26578 unacceptable regeneration throws and leaves no row`() {
        val long = "x".repeat(65)
        mapOf(
            "empty packVersions" to regeneration(packs = emptyList()),
            "21 items" to regeneration(packs = (1..21).map { PackVersionFact("P$it", "1") }),
            "two MDE items" to regeneration(packs = listOf(mde, PackVersionFact("MDE", "MDE-8"))),
            "blank release" to regeneration(release = " "),
            "empty release" to regeneration(release = ""),
            "65-character release" to regeneration(release = long),
            "canonical version 0" to regeneration(canonicalVersion = 0),
            "negative canonical version" to regeneration(canonicalVersion = -1),
            "blank packType" to regeneration(packs = listOf(PackVersionFact(" ", "MDE-7"))),
            "65-character packType" to regeneration(packs = listOf(PackVersionFact(long, "MDE-7"))),
            "blank version" to regeneration(packs = listOf(PackVersionFact("MDE", ""))),
            "65-character version" to regeneration(packs = listOf(PackVersionFact("MDE", long))),
            "bad second item" to regeneration(packs = listOf(mde, PackVersionFact("X", " "))),
        ).forEach { (case, unacceptable) ->
            withClue(case) {
                val doc = UUID.randomUUID()
                shouldThrow<IllegalArgumentException> { stage(facts(doc, unacceptable)) }
                rows(doc).size shouldBe 0
            }
        }
    }

    @Test
    fun `INV-AC-26578 regeneration at the schema bounds is staged`() {
        val doc = UUID.randomUUID()
        val max = "x".repeat(64)
        val packs = listOf(PackVersionFact(max, max)) + (2..20).map { PackVersionFact("P$it", "1") }
        stage(facts(doc, regeneration(canonicalVersion = 1, release = max, packs = packs)))
        val key =
            rows(doc)
                .single()
                .second
                .path("regenerationKey")
        key.path("packVersions").size() shouldBe 20
        key.path("specificationVersion").asString() shouldBe max
    }

    @Test
    fun `INV-AC-26578 regeneration texts are escaped as JSON strings`() {
        val doc = UUID.randomUUID()
        val odd = "a\"b\\c\nd"
        stage(facts(doc, regeneration(release = odd, packs = listOf(PackVersionFact(odd, odd)))))
        val key =
            rows(doc)
                .single()
                .second
                .path("regenerationKey")
        key.path("specificationVersion").asString() shouldBe odd
        key
            .path("packVersions")
            .path(0)
            .path("packRef")
            .asString() shouldBe odd
        key
            .path("packVersions")
            .path(0)
            .path("version")
            .asString() shouldBe odd
    }

    private companion object {
        const val SHA_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
