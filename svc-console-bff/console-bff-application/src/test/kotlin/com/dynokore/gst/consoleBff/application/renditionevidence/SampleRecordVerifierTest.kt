package com.dynokore.gst.consoleBff.application.renditionevidence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The shared vector of the sample check, the same literals as in the payload cases. */
internal object SampleVector {
    const val ENTITY = "ENT-0001"
    const val CHECK = "chk-0001"
    const val SHA256 = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab4"

    /** `%PDF-1.4` and a line feed. */
    fun bytes(): ByteArray =
        java.util.Base64
            .getDecoder()
            .decode("JVBERi0xLjQK")

    fun record(
        entityRef: String = ENTITY,
        checkId: String = CHECK,
        sampleContent: ByteArray = bytes(),
        sampleSha256: String = SHA256,
    ) = SampleRecord(
        checkId = checkId,
        entityRef = entityRef,
        scenario = "SELF_BILLING",
        submissionType = "TYPE_2",
        sampleContent = sampleContent,
        sampleSha256 = sampleSha256,
        result = "FAILED",
        textFound = true,
        missingWording = listOf("Buyer Created Tax Invoice"),
        checkedAt = java.time.Instant.parse("2026-03-20T03:20:00Z"),
        checkedBy = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b",
        findingCode = "PAY-014",
    )

    fun meta(
        entityRef: String = ENTITY,
        checkId: String = CHECK,
        sampleSha256: String = SHA256,
    ) = SampleMeta(
        checkId = checkId,
        entityRef = entityRef,
        scenario = "SELF_BILLING",
        submissionType = "TYPE_2",
        sampleSha256 = sampleSha256,
        result = "FAILED",
        textFound = true,
        missingWording = listOf("Buyer Created Tax Invoice"),
        checkedAt = java.time.Instant.parse("2026-03-20T03:20:00Z"),
        checkedBy = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b",
        findingCode = "PAY-014",
    )

    fun oneByteChanged(): ByteArray = bytes().also { it[0] = (it[0] + 1).toByte() }

    /** The sampleSha256 with its last hex digit changed. */
    const val OTHER_SHA256 = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab5"
}

/** INV-AC-27771: the identity and content checks of a sample record. */
class SampleRecordVerifierTest {
    private val reference = "${SampleVector.ENTITY}/${SampleVector.CHECK}"

    private fun failed(rule: SampleCheckRule) = Verification.Failed(reference, rule)

    private fun identity(
        record: SampleRecord = SampleVector.record(),
        meta: SampleMeta = SampleVector.meta(record.entityRef, record.checkId),
    ): List<Verification> =
        listOf(
            SampleRecordVerifier.identity(SampleVector.ENTITY, SampleVector.CHECK, record),
            SampleRecordVerifier.identity(SampleVector.ENTITY, SampleVector.CHECK, meta),
        )

    @Test
    fun `INV-AC-27771 the vector is verified in identity and content`() {
        identity() shouldBe listOf(Verification.Verified, Verification.Verified)
        SampleRecordVerifier.content(SampleVector.record()) shouldBe Verification.Verified
    }

    @Test
    fun `INV-AC-27771 a record of another checkId fails identity, look-alikes included`() {
        for (other in listOf("chk-0002", "CHK-0001", "chk-0001 ", "chk-000", "chk-00011", "")) {
            identity(SampleVector.record(checkId = other)) shouldBe
                List(2) { failed(SampleCheckRule.CHECK_ID) }
        }
    }

    @Test
    fun `INV-AC-27771 a record of another entity fails identity, look-alikes included`() {
        for (other in listOf("ENT-0002", "ent-0001", "ENT-0001 ", "ENT-000", "ENT-00011", "ENT-0001/", "*", "")) {
            identity(SampleVector.record(entityRef = other)) shouldBe
                List(2) { failed(SampleCheckRule.ENTITY_REF) }
        }
    }

    @Test
    fun `INV-AC-27771 an empty checkId fails identity even when the record holds it too`() {
        SampleRecordVerifier.identity(SampleVector.ENTITY, "", SampleVector.record(checkId = "")) shouldBe
            Verification.Failed("${SampleVector.ENTITY}/", SampleCheckRule.REFERENCE)
    }

    @Test
    fun `INV-AC-27771 a reference is the entity, a slash and a non-empty checkId`() {
        SampleRecordVerifier.checkIdOf(SampleVector.ENTITY, reference) shouldBe SampleVector.CHECK
        val broken =
            listOf(
                "",
                SampleVector.CHECK,
                SampleVector.ENTITY,
                "${SampleVector.ENTITY}/",
                "${SampleVector.ENTITY}${SampleVector.CHECK}",
                "${SampleVector.ENTITY}-${SampleVector.CHECK}",
                "/$reference",
                " $reference",
                "ent-0001/chk-0001",
                "ENT-00011/chk-0001",
                "ENT-0002/chk-0001",
                "X/$reference",
            )
        broken.map { SampleRecordVerifier.checkIdOf(SampleVector.ENTITY, it) } shouldBe broken.map { null }
    }

    @Test
    fun `INV-AC-27771 one changed byte or one changed hex digit fails the hash comparison`() {
        val broken =
            listOf(
                SampleVector.record(sampleContent = SampleVector.oneByteChanged()),
                SampleVector.record(sampleContent = ByteArray(0)),
                SampleVector.record(sampleContent = SampleVector.bytes() + 0.toByte()),
                SampleVector.record(sampleSha256 = SampleVector.OTHER_SHA256),
            )
        broken.map(SampleRecordVerifier::content) shouldBe broken.map { failed(SampleCheckRule.SAMPLE_SHA256_MATCH) }
    }

    @Test
    fun `INV-AC-27771 a sampleSha256 that is not 64 lowercase hex characters fails`() {
        val malformed =
            listOf(
                SampleVector.SHA256.uppercase(),
                SampleVector.SHA256.replaceFirst('e', 'E'),
                SampleVector.SHA256.dropLast(1),
                SampleVector.SHA256 + "4",
                " ${SampleVector.SHA256}",
                "${SampleVector.SHA256}\n",
                "sha256:${SampleVector.SHA256}",
                "*",
                "",
            )
        malformed.map { SampleRecordVerifier.content(SampleVector.record(sampleSha256 = it)) } shouldBe
            malformed.map { failed(SampleCheckRule.SAMPLE_SHA256_FORMAT) }
    }
}
