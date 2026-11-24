package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.SampleResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadRenditionSampleMetadataTest {
    private fun useCase(
        holder: Boolean,
        reader: CountingReader,
        authority: ScriptedAuthority = ScriptedAuthority(holder),
    ) = ReadRenditionSampleMetadata(authority, reader)

    @Test
    fun `INV-AC-27768 the vector gives the metadata with every member equal and open true`() {
        val reader = readerFixture()
        val o =
            useCase(true, reader).execute("u1", SharedVector.ENTITY, SharedVector.CHECK_ID)
                as ReadRenditionSampleMetadataOutcome.Found
        o.metadata shouldBe SharedVector.metadata(SharedVector.check())
        o.open shouldBe true
    }

    @Test
    fun `INV-AC-27768 metadata of E1 gives open true and of a PASSED check of E2 gives open false`() {
        val reader = readerFixture()
        (useCase(true, reader).execute("u1", "E1", "c1") as ReadRenditionSampleMetadataOutcome.Found).open shouldBe true
        val o = useCase(true, reader).execute("u1", "E2", "c2") as ReadRenditionSampleMetadataOutcome.Found
        o.metadata.result shouldBe SampleResult.PASSED
        o.open shouldBe false
    }

    @Test
    fun `INV-AC-27768 metadata non-holder gets Forbidden and the reader counts zero calls`() {
        val reader = readerFixture()
        useCase(false, reader).execute("u1", "E1", "c1") shouldBe ReadRenditionSampleMetadataOutcome.Forbidden
        reader.calls shouldBe 0
    }

    @Test
    fun `INV-AC-27768 metadata unknown checkId and check of another entity get NotFound`() {
        val reader = readerFixture()
        useCase(true, reader).execute("u1", "E1", "nope") shouldBe ReadRenditionSampleMetadataOutcome.NotFound
        useCase(true, reader).execute("u1", "E1", "c2") shouldBe ReadRenditionSampleMetadataOutcome.NotFound
    }

    @Test
    fun `INV-AC-27768 metadata makes one findMetadata call and no find call and asks the exact key`() {
        val reader = readerFixture()
        val authority = ScriptedAuthority(true)
        useCase(true, reader, authority).execute("u1", "E1", "c1")
        reader.metadataFinds shouldBe 1
        reader.finds shouldBe 0
        authority.asked shouldBe listOf(Triple("u1", "rendition-evidence.view", "E1"))
    }

    @Test
    fun `INV-AC-27768 no member of the metadata types is named sampleContent or artefactBytes`() {
        val names =
            listOf(StoredSampleMetadata::class, ReadRenditionSampleMetadataOutcome.Found::class)
                .flatMap { c -> c.java.declaredFields.map { it.name } + c.java.declaredMethods.map { it.name } }
        val banned = setOf("sampleContent", "artefactBytes", "getSampleContent", "getArtefactBytes")
        names.none { it in banned } shouldBe true
        names.contains("getSampleSha256") shouldBe true
    }
}
