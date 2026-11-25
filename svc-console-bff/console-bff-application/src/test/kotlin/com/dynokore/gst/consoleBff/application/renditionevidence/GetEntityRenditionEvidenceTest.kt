package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** INV-AC-27606: the join of the two owner reads, over fake ports. */
class GetEntityRenditionEvidenceTest {
    private data class Asked(
        val entityRef: String,
        val limit: Int,
        val cursor: String?,
    )

    private val attestation =
        AttestationRecord("TAX_INVOICE", "AGENT", "identity:USR-009", Instant.parse("2026-10-18T09:30:00Z"), 1)
    private val sample =
        SampleCheckArtefactRecord("SAMPLE_CHECK", "SC-1", "a".repeat(64), Instant.parse("2026-04-01T01:00:00Z"))

    private val askedAttestations = mutableListOf<Asked>()
    private val askedSamples = mutableListOf<Asked>()
    private var attestationReply: PageReply<AttestationRecord> = PageReply.Ok(EvidencePage(listOf(attestation), "a-2"))
    private var sampleReply: PageReply<SampleCheckArtefactRecord> = PageReply.Ok(EvidencePage(listOf(sample), "s-2"))

    private val useCase =
        GetEntityRenditionEvidence(
            { entityRef, limit, cursor ->
                askedAttestations += Asked(entityRef, limit, cursor)
                attestationReply
            },
            { entityRef, limit, cursor ->
                askedSamples += Asked(entityRef, limit, cursor)
                sampleReply
            },
        )

    private fun refusal(
        status: Int,
        owner: String = "SRT",
    ) = OwnerRefusal(status, "$owner-$status", "T", "BLOCK", "MANUAL", "R", "D")

    private fun get() = useCase.get("E1", LIMIT, "a-1", "s-1")

    @Test
    fun `INV-AC-27606 both pages of E1 are composed, each asked once with the limit and its own cursor`() {
        get() shouldBe
            RenditionEvidenceResult.Composed(
                EvidencePage(listOf(attestation), "a-2"),
                EvidencePage(listOf(sample), "s-2"),
            )
        askedAttestations shouldBe listOf(Asked("E1", LIMIT, "a-1"))
        askedSamples shouldBe listOf(Asked("E1", LIMIT, "s-1"))
    }

    @Test
    fun `INV-AC-27606 without cursors none is passed and a last page has no nextCursor`() {
        attestationReply = PageReply.Ok(EvidencePage(listOf(attestation), null))
        sampleReply = PageReply.Ok(EvidencePage(emptyList(), null))
        useCase.get("E1", LIMIT, null, null) shouldBe
            RenditionEvidenceResult.Composed(
                EvidencePage(listOf(attestation), null),
                EvidencePage(emptyList(), null),
            )
        askedAttestations shouldBe listOf(Asked("E1", LIMIT, null))
        askedSamples shouldBe listOf(Asked("E1", LIMIT, null))
    }

    @Test
    fun `INV-AC-27606 each page keeps its own nextCursor when only one list ends`() {
        sampleReply = PageReply.Ok(EvidencePage(listOf(sample), null))
        val result = get() as RenditionEvidenceResult.Composed
        result.attestations.nextCursor shouldBe "a-2"
        result.sampleChecks.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-27606 nothing is stored, so a second call asks both owners again`() {
        get()
        attestationReply = PageReply.Unavailable("down")
        get() shouldBe RenditionEvidenceResult.Unavailable("down")
        askedAttestations.size shouldBe 2
        askedSamples.size shouldBe 2
    }

    @Test
    fun `INV-AC-27606 an unavailable attestation read gives Unavailable and no partial page`() {
        attestationReply = PageReply.Unavailable("tenancy down")
        get() shouldBe RenditionEvidenceResult.Unavailable("tenancy down")
        askedAttestations.size shouldBe 1
        askedSamples.size shouldBe 1
    }

    @Test
    fun `INV-AC-27606 an unavailable sample check read gives Unavailable and no partial page`() {
        sampleReply = PageReply.Unavailable("evidence down")
        get() shouldBe RenditionEvidenceResult.Unavailable("evidence down")
    }

    @Test
    fun `INV-AC-27606 Unavailable of one owner wins over a refusal of the other, on both sides`() {
        for (status in listOf(400, 403, 404)) {
            attestationReply = refused(status)
            sampleReply = PageReply.Unavailable("evidence down")
            get() shouldBe RenditionEvidenceResult.Unavailable("evidence down")
            attestationReply = PageReply.Unavailable("tenancy down")
            sampleReply = refused(status)
            get() shouldBe RenditionEvidenceResult.Unavailable("tenancy down")
        }
    }

    @Test
    fun `INV-AC-27606 Forbidden, NotFound and Invalid of either owner are relayed with its envelope`() {
        val expected =
            mapOf(
                403 to RenditionEvidenceResult.Forbidden(refusal(403)),
                404 to RenditionEvidenceResult.NotFound(refusal(404)),
                400 to RenditionEvidenceResult.Invalid(refusal(400)),
            )
        for ((status, result) in expected) {
            attestationReply = refused(status)
            sampleReply = PageReply.Ok(EvidencePage(listOf(sample), null))
            get() shouldBe result
            attestationReply = PageReply.Ok(EvidencePage(listOf(attestation), null))
            sampleReply = refused(status)
            get() shouldBe result
        }
    }

    @Test
    fun `INV-AC-27606 two different refusals give Forbidden before NotFound before Invalid in either position`() {
        for ((winner, loser) in listOf(403 to 404, 403 to 400, 404 to 400)) {
            attestationReply = refused(winner)
            sampleReply = refused(loser)
            get() shouldBe relayed(refusal(winner))
            attestationReply = refused(loser)
            sampleReply = refused(winner)
            get() shouldBe relayed(refusal(winner))
        }
    }

    @Test
    fun `INV-AC-27606 two refusals of the same kind relay the envelope of the attestation read`() {
        for (status in listOf(400, 403, 404)) {
            val ofAttestations = refusal(status, "ATT")
            val ofSamples = refusal(status, "SMP")
            attestationReply = refused(ofAttestations)
            sampleReply = refused(ofSamples)
            get() shouldBe relayed(ofAttestations)
            attestationReply = refused(ofSamples)
            sampleReply = refused(ofAttestations)
            get() shouldBe relayed(ofSamples)
        }
    }

    private fun relayed(refusal: OwnerRefusal): RenditionEvidenceResult =
        when (refusal.status) {
            403 -> RenditionEvidenceResult.Forbidden(refusal)
            404 -> RenditionEvidenceResult.NotFound(refusal)
            else -> RenditionEvidenceResult.Invalid(refusal)
        }

    private fun refused(status: Int): PageReply<Nothing> = refused(refusal(status))

    private fun refused(refusal: OwnerRefusal): PageReply<Nothing> =
        when (refusal.status) {
            403 -> PageReply.Forbidden(refusal)
            404 -> PageReply.NotFound(refusal)
            else -> PageReply.Invalid(refusal)
        }

    private companion object {
        const val LIMIT = 25
    }
}
