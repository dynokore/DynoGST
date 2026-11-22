package com.dynokore.gst.controlPlane.adapters.stage

import com.dynokore.gst.controlPlane.application.pack.PackContentHasher
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.SignOutcome
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockExecuteContext
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The stage half of the pack staging gate (INV-REQ-2662, INV-AC-26354) without a database: the stage runs over fakes
 * and a jOOQ context over a mock connection that records the bind values of each call of `record_staging_result`.
 * The publish half is proved in PackOperationsTest.
 */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class PackStageTest {
    private val h1 = "a".repeat(64)
    private val h2 = "b".repeat(64)

    private val calls = mutableListOf<List<Any?>>()

    private val dsl: DSLContext =
        DSL.using(
            MockConnection(
                MockDataProvider { ctx: MockExecuteContext ->
                    if (ctx.sql().contains("record_staging_result")) calls += ctx.bindings().toList()
                    val field = DSL.field("record_staging_result", SQLDataType.UUID)
                    val result = DSL.using(SQLDialect.POSTGRES).newResult(field)
                    result.add(
                        DSL.using(SQLDialect.POSTGRES).newRecord(field).also { it.set(field, UUID.randomUUID()) },
                    )
                    arrayOf(MockResult(1, result))
                },
            ),
            SQLDialect.POSTGRES,
        )

    private val signed: RegulatoryPack =
        (
            RegulatoryPack
                .draft(
                    PackType("SCOPE"),
                    PackVersion("2026.3"),
                    listOf("IRAS e-Tax Guide 2026-08"),
                    ActorId("author"),
                    LocalDate.parse("2026-10-01"),
                    PackContents("{\"rules\":[]}"),
                ).sign(ActorId("signer"), "KEY-REG-01", null, false, Instant.parse("2026-10-01T00:00:00Z"))
                as SignOutcome.Signed
        ).pack

    private val repository =
        object : PackRepository {
            override fun find(ref: PackRef) = signed.takeIf { it.ref == ref }

            override fun save(pack: RegulatoryPack) = error("the stage never saves a pack")

            override fun latestPublished(type: PackType): PackRef? = null
        }

    private var reads = 0

    private val hasher =
        PackContentHasher {
            reads++
            ContentHash(if (reads == 1) h1 else h2)
        }

    private class Sandbox(
        private val rejected: Set<String> = emptySet(),
    ) : SandboxSubmitter {
        override val target = "SIMULATOR"

        override fun submit(submission: StagedSubmission): SandboxVerdict =
            if (submission.documentRef in rejected) {
                SandboxVerdict.Rejected("REJECT")
            } else {
                SandboxVerdict.Accepted
            }
    }

    private fun stage(
        sandbox: SandboxSubmitter = Sandbox(),
        pipeline: PackPipeline,
    ) = PackStage(dsl, repository, hasher, pipeline, sandbox)

    private fun replayOf(vararg docs: String) =
        PackPipeline { PeriodReplay("2026-09", docs.map { StagedSubmission(it, "payload $it") }) }

    @Test
    fun `INV-AC-26354 the stage records PASSED over the hash of the content it staged when every submission is accepted`() {
        val outcome = stage(pipeline = replayOf("DOC-1", "DOC-2")).run(signed.ref)
        outcome.result shouldBe StagingResult.PASSED
        calls.size shouldBe 1
        calls.single().take(3) shouldBe listOf(signed.ref.value, "PASSED", h1)
    }

    @Test
    fun `INV-AC-26354 a pack with a rejected submission is recorded FAILED and the detail names the rejected document`() {
        val outcome = stage(Sandbox(setOf("DOC-9")), replayOf("DOC-1", "DOC-9")).run(signed.ref)
        outcome.result shouldBe StagingResult.FAILED
        outcome.detail shouldBe "sandbox rejected DOC-9"
        calls.size shouldBe 1
        calls.single()[1] shouldBe "FAILED"
        calls.none { it[1] == "PASSED" } shouldBe true
    }

    @Test
    fun `INV-AC-26354 a replay that produced no submission or failed is recorded FAILED`() {
        stage(pipeline = replayOf()).run(signed.ref).result shouldBe StagingResult.FAILED
        calls.size shouldBe 1
        calls.single()[1] shouldBe "FAILED"
        calls.clear()
        stage(pipeline = { throw IllegalStateException("replay broke") }).run(signed.ref).result shouldBe
            StagingResult.FAILED
        calls.size shouldBe 1
        calls.single()[1] shouldBe "FAILED"
    }

    @Test
    fun `INV-AC-26354 the hash recorded is the one read before the replay, even when the content changes during it`() {
        // the hasher answers H1 on its first reading and H2 afterwards; the replay reads it, as a changed content would
        val pipeline =
            PackPipeline {
                hasher.hash(it)
                PeriodReplay("2026-09", listOf(StagedSubmission("DOC-1", "payload")))
            }
        stage(pipeline = pipeline).run(signed.ref).result shouldBe StagingResult.PASSED
        calls.size shouldBe 1
        calls.single()[2] shouldBe h1
        reads shouldBe 2
    }
}
