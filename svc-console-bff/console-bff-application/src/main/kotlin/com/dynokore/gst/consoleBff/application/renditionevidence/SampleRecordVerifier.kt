package com.dynokore.gst.consoleBff.application.renditionevidence

import java.security.MessageDigest
import java.util.HexFormat

/** The check of a sample record that failed. */
enum class SampleCheckRule {
    /** The artefact reference is not the entity of the path, a slash and a non-empty checkId. */
    REFERENCE,

    /** The entityRef of the record is not the entity of the path. */
    ENTITY_REF,

    /** The checkId of the record is not the checkId of the reference. */
    CHECK_ID,

    /** The sampleSha256 of the record is not 64 lowercase hex characters. */
    SAMPLE_SHA256_FORMAT,

    /** The SHA-256 of the sample bytes is not the sampleSha256 of the record. */
    SAMPLE_SHA256_MATCH,
}

/** The outcome of one verification. A failure names the reference and the rule, and never holds a byte. */
sealed interface Verification {
    data object Verified : Verification

    data class Failed(
        val reference: String,
        val rule: SampleCheckRule,
    ) : Verification
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * The fail-closed checks of a payload sample record (INV-REQ-1036, NIST SP 800-53 SI-7). Every comparison is exact
 * text. No record field is serialised.
 */
object SampleRecordVerifier {
    /** The checkId of [reference], or null when it is not [entityRef], a slash and a non-empty checkId. */
    fun checkIdOf(
        entityRef: String,
        reference: String,
    ): String? {
        val prefix = "$entityRef/"
        return if (reference.startsWith(prefix)) reference.substring(prefix.length).ifEmpty { null } else null
    }

    fun identity(
        entityRef: String,
        checkId: String,
        record: SampleRecord,
    ): Verification = identity(entityRef, checkId, record.entityRef, record.checkId)

    fun identity(
        entityRef: String,
        checkId: String,
        meta: SampleMeta,
    ): Verification = identity(entityRef, checkId, meta.entityRef, meta.checkId)

    /** The sample bytes hash to the sampleSha256 of the same record. It does not check the identity. */
    fun content(record: SampleRecord): Verification {
        val digest = MessageDigest.getInstance("SHA-256").digest(record.sampleContent)
        val rule =
            when {
                !SHA256_HEX.matches(record.sampleSha256) -> SampleCheckRule.SAMPLE_SHA256_FORMAT
                HexFormat.of().formatHex(digest) != record.sampleSha256 -> SampleCheckRule.SAMPLE_SHA256_MATCH
                else -> null
            }
        return outcome("${record.entityRef}/${record.checkId}", rule)
    }

    private fun identity(
        entityRef: String,
        checkId: String,
        recordEntityRef: String,
        recordCheckId: String,
    ): Verification {
        val rule =
            when {
                checkId.isEmpty() -> SampleCheckRule.REFERENCE
                recordEntityRef != entityRef -> SampleCheckRule.ENTITY_REF
                recordCheckId != checkId -> SampleCheckRule.CHECK_ID
                else -> null
            }
        return outcome("$entityRef/$checkId", rule)
    }

    private fun outcome(
        reference: String,
        rule: SampleCheckRule?,
    ): Verification = if (rule == null) Verification.Verified else Verification.Failed(reference, rule)
}
