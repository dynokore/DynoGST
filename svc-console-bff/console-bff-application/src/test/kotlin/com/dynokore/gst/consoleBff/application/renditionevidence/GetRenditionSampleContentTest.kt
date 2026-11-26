package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** INV-AC-27771: the single sample read, over a fake record port. */
class GetRenditionSampleContentTest {
    private var reply: SampleReply<SampleRecord> = SampleReply.Ok(SampleVector.record())
    private val asked = mutableListOf<Pair<String, String>>()

    private val useCase =
        GetRenditionSampleContent { entityRef, checkId ->
            asked += entityRef to checkId
            reply
        }

    private fun execute() = useCase.execute(SampleVector.ENTITY, SampleVector.CHECK)

    private val refusal = OwnerRefusal(403, "SRT-403", "T", "BLOCK", "MANUAL", "R", "D")

    private fun integrityFailure(rule: SampleCheckRule) =
        SampleContentResult.IntegrityFailure(
            Verification.Failed("${SampleVector.ENTITY}/${SampleVector.CHECK}", rule),
        )

    @Test
    fun `INV-AC-27771 the vector gives its 9 bytes with its sampleSha256 and checkId, after one record read`() {
        val result = execute().shouldBeInstanceOf<SampleContentResult.Content>()
        result.sampleContent.size shouldBe 9
        result.sampleContent.toList() shouldBe "%PDF-1.4\n".toByteArray(Charsets.US_ASCII).toList()
        result.sampleSha256 shouldBe SampleVector.SHA256
        result.checkId shouldBe SampleVector.CHECK
        asked shouldBe listOf(SampleVector.ENTITY to SampleVector.CHECK)
    }

    @Test
    fun `INV-AC-27771 the result of the vector holds the checkId, the hash and the bytes and nothing else`() {
        SampleContentResult.Content::class.java.declaredFields
            .map { it.name }
            .toSet() shouldBe setOf("checkId", "sampleSha256", "sampleContent")
    }

    @Test
    fun `INV-AC-27771 the sample bytes changed by one byte give an integrity failure and no bytes`() {
        reply = SampleReply.Ok(SampleVector.record(sampleContent = SampleVector.oneByteChanged()))
        execute() shouldBe integrityFailure(SampleCheckRule.SAMPLE_SHA256_MATCH)
    }

    @Test
    fun `INV-AC-27771 a sampleSha256 changed by one hex digit gives an integrity failure and no bytes`() {
        reply = SampleReply.Ok(SampleVector.record(sampleSha256 = SampleVector.OTHER_SHA256))
        execute() shouldBe integrityFailure(SampleCheckRule.SAMPLE_SHA256_MATCH)
    }

    @Test
    fun `INV-AC-27771 a sampleSha256 in upper case gives an integrity failure and no bytes`() {
        reply = SampleReply.Ok(SampleVector.record(sampleSha256 = SampleVector.SHA256.uppercase()))
        execute() shouldBe integrityFailure(SampleCheckRule.SAMPLE_SHA256_FORMAT)
    }

    @Test
    fun `INV-AC-27771 a record of another checkId or entity gives an integrity failure and no bytes`() {
        reply = SampleReply.Ok(SampleVector.record(checkId = "chk-0002"))
        execute() shouldBe integrityFailure(SampleCheckRule.CHECK_ID)
        reply = SampleReply.Ok(SampleVector.record(entityRef = "ENT-0002"))
        execute() shouldBe integrityFailure(SampleCheckRule.ENTITY_REF)
    }

    @Test
    fun `INV-AC-27771 Forbidden, NotFound and Invalid are relayed with the envelope and no bytes`() {
        reply = SampleReply.Forbidden(refusal)
        execute() shouldBe SampleContentResult.Forbidden(refusal)
        reply = SampleReply.NotFound(refusal)
        execute() shouldBe SampleContentResult.NotFound(refusal)
        reply = SampleReply.Invalid(refusal)
        execute() shouldBe SampleContentResult.Invalid(refusal)
    }

    @Test
    fun `INV-AC-27771 an unavailable payload read gives Unavailable and no bytes`() {
        reply = SampleReply.Unavailable("payload down")
        execute() shouldBe SampleContentResult.Unavailable("payload down")
        asked.size shouldBe 1
    }

    @Test
    fun `INV-AC-27771 the single read holds the record port only, so it takes no hash from a metadata read`() {
        GetRenditionSampleContent::class.java.declaredConstructors
            .single()
            .parameterTypes
            .toList() shouldBe listOf(SampleRecordRead::class.java)
    }
}
