package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

private typealias Pair3 = Pair<String, SubmissionType>

/** The shared vector (the same literal in the payload and the console-bff cases). */
internal object SharedVector {
    const val ENTITY = "ENT-0001"
    const val CHECK_ID = "chk-0001"
    const val SHA = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab4"
    const val BY = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b"
    val at: Instant = Instant.parse("2026-03-20T03:20:00Z")
    val sample = "%PDF-1.4\n".toByteArray(Charsets.UTF_8)
    val artefact =
        (
            "{\"checkId\":\"chk-0001\",\"checkedAt\":\"2026-03-20T03:20:00Z\",\"checkedBy\":\"$BY\"," +
                "\"entityRef\":\"ENT-0001\",\"missingWording\":[\"Buyer Created Tax Invoice\"],\"result\":\"FAILED\"," +
                "\"sampleSha256\":\"$SHA\",\"scenario\":\"SELF_BILLING\",\"submissionType\":\"TYPE_2\",\"textFound\":true}"
        ).toByteArray(Charsets.UTF_8)

    fun check(
        entity: String = ENTITY,
        checkId: String = CHECK_ID,
        result: SampleResult = SampleResult.FAILED,
    ) = StoredSampleCheck(
        checkId,
        entity,
        "SELF_BILLING",
        SubmissionType.TYPE_2,
        sample,
        SHA,
        result,
        listOf("Buyer Created Tax Invoice"),
        true,
        at,
        BY,
        artefact,
    )

    fun metadata(c: StoredSampleCheck) =
        StoredSampleMetadata(
            c.checkId,
            c.entityRef,
            c.scenario,
            c.submissionType,
            c.sampleSha256,
            c.result,
            c.missingWording,
            c.textFound,
            c.checkedAt,
            c.checkedBy,
        )
}

/** In-memory reader counting every call; E1 pair A PASSED after FAILED, pair B FAILED; E2 all PASSED. */
internal class CountingReader(
    private val checks: List<StoredSampleCheck>,
    private val standing: Map<String, Map<Pair3, SampleResult>>,
) : RenditionSampleReader {
    var finds = 0
    var metadataFinds = 0
    var latests = 0

    override fun find(
        entityRef: String,
        checkId: String,
    ): StoredSampleCheck? {
        finds++
        return checks.firstOrNull { it.entityRef == entityRef && it.checkId == checkId }
    }

    override fun findMetadata(
        entityRef: String,
        checkId: String,
    ): StoredSampleMetadata? {
        metadataFinds++
        return checks.firstOrNull { it.entityRef == entityRef && it.checkId == checkId }?.let(SharedVector::metadata)
    }

    override fun latest(entityRef: String): Map<Pair3, SampleResult> {
        latests++
        return standing[entityRef].orEmpty()
    }

    val calls get() = finds + metadataFinds + latests
}

internal class ScriptedAuthority(
    private val holder: Boolean,
) : OverrideAuthority {
    val asked = mutableListOf<Triple<String, String, String>>()

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        asked += Triple(userId, permission, entityRef)
        return holder
    }
}

internal fun standingFixture(): Map<String, Map<Pair3, SampleResult>> =
    mapOf(
        "E1" to
            mapOf(
                ("A" to SubmissionType.TYPE_1A) to SampleResult.PASSED,
                ("B" to SubmissionType.TYPE_1A) to SampleResult.FAILED,
            ),
        "E2" to mapOf(("A" to SubmissionType.TYPE_1A) to SampleResult.PASSED),
        SharedVector.ENTITY to mapOf(("SELF_BILLING" to SubmissionType.TYPE_2) to SampleResult.FAILED),
    )

internal fun readerFixture() =
    CountingReader(
        listOf(
            SharedVector.check("E1", "c1"),
            SharedVector.check("E2", "c2", SampleResult.PASSED),
            SharedVector.check(),
        ),
        standingFixture(),
    )

class ReadRenditionSampleCheckTest {
    private fun useCase(
        holder: Boolean,
        reader: CountingReader,
        authority: ScriptedAuthority = ScriptedAuthority(holder),
    ) = ReadRenditionSampleCheck(authority, reader)

    @Test
    fun `INV-AC-27768 a holder gets the check and open true for E1 because pair B is FAILED`() {
        val reader = readerFixture()
        val o = useCase(true, reader).execute("u1", "E1", "c1") as ReadRenditionSampleCheckOutcome.Found
        o.check shouldBe SharedVector.check("E1", "c1")
        o.open shouldBe true
    }

    @Test
    fun `INV-AC-27768 a holder gets open false for E2 and for an entity with no check results`() {
        val reader = readerFixture()
        (useCase(true, reader).execute("u1", "E2", "c2") as ReadRenditionSampleCheckOutcome.Found).open shouldBe false
        val bare = CountingReader(listOf(SharedVector.check("E3", "c3")), emptyMap())
        (useCase(true, bare).execute("u1", "E3", "c3") as ReadRenditionSampleCheckOutcome.Found).open shouldBe false
    }

    @Test
    fun `INV-AC-27768 a non-holder gets Forbidden and the reader is not called`() {
        val reader = readerFixture()
        useCase(false, reader).execute("u1", "E1", "c1") shouldBe ReadRenditionSampleCheckOutcome.Forbidden
        reader.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27768 an unknown checkId and a check of another entity get NotFound`() {
        val reader = readerFixture()
        useCase(true, reader).execute("u1", "E1", "nope") shouldBe ReadRenditionSampleCheckOutcome.NotFound
        useCase(true, reader).execute("u1", "E1", "c2") shouldBe ReadRenditionSampleCheckOutcome.NotFound
    }

    @Test
    fun `INV-AC-27768 the key asked is exactly rendition-evidence dot view for the entity of the call`() {
        val authority = ScriptedAuthority(true)
        useCase(true, readerFixture(), authority).execute("u9", "E2", "c2")
        authority.asked shouldBe listOf(Triple("u9", "rendition-evidence.view", "E2"))
        RENDITION_EVIDENCE_VIEW shouldBe "rendition-evidence.view"
    }

    @Test
    fun `INV-AC-27768 the shared vector check keeps the vector bytes and open true`() {
        val o =
            useCase(true, readerFixture()).execute("u1", SharedVector.ENTITY, SharedVector.CHECK_ID)
                as ReadRenditionSampleCheckOutcome.Found
        o.check.artefactBytes.contentEquals(SharedVector.artefact) shouldBe true
        o.check.sampleContent.contentEquals(SharedVector.sample) shouldBe true
        o.open shouldBe true
    }
}
