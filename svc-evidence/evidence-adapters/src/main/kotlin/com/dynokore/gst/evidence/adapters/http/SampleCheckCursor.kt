package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.artefact.SampleCheckCursors
import com.dynokore.gst.evidence.application.artefact.SampleCheckPosition
import com.dynokore.platform.crypto.MacAlgorithm
import com.dynokore.platform.crypto.MacKey
import com.dynokore.platform.crypto.Macs
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The cursor of `listRenditionSampleChecks`: `v1.` + base64url(position, issue time) + `.` + base64url(tag), the tag
 * a HMAC-SHA256 of `Macs` (CS-53) over the layout, the entityRef, the acting user ID (never a role) and the position.
 * The entityRef, the user ID and the key are not in the token. The key is read through [SecretStore] at every use,
 * never cached; the previous key is accepted for verification only. Every refusal answers null alike.
 */
class SampleCheckCursor(
    private val secrets: SecretStore,
) : SampleCheckCursors {
    /** A missing, blank or short current key throws: a 5xx, never an unsigned page. */
    override fun issue(
        entityRef: String,
        userId: String,
        last: SampleCheckPosition,
        now: Instant,
    ): String {
        val payload = payloadOf(last, now)
        val tag = Macs.tag(currentKey(), signed(entityRef, userId, last))
        return "$LAYOUT.${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(tag)}"
    }

    override fun open(
        token: String,
        entityRef: String,
        userId: String,
        now: Instant,
    ): SampleCheckPosition? {
        val parts = token.split('.')
        val payload = if (parts.size == PARTS && parts[0] == LAYOUT) decoded(parts[1]) else null
        val tag = if (payload != null) decoded(parts[2]) else null
        val key = currentKey()
        val parsed = payload?.let { parse(it, now) }
        val genuine =
            parsed != null && tag != null &&
                listOfNotNull(key, previousKey()).any { Macs.verify(it, signed(entityRef, userId, parsed), tag) }
        return if (genuine) parsed else null
    }

    private fun payloadOf(
        last: SampleCheckPosition,
        now: Instant,
    ): ByteArray =
        "${last.recordedAt}\n${now.toEpochMilli()}\n${last.hash}\n${last.reference}".toByteArray(Charsets.UTF_8)

    private fun parse(
        payload: ByteArray,
        now: Instant,
    ): SampleCheckPosition? {
        val fields = String(payload, Charsets.UTF_8).split('\n', limit = FIELDS)
        val recorded = fields.getOrNull(0)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val issued = fields.getOrNull(1)?.toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        val hash = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val reference = fields.getOrNull(FIELDS - 1)?.takeIf { it.isNotEmpty() }
        val fresh = issued != null && !issued.isAfter(now) && !issued.plus(MAX_AGE).isBefore(now)
        return if (recorded != null && hash != null && reference != null && fresh) {
            SampleCheckPosition(recorded, reference, hash)
        } else {
            null
        }
    }

    private fun currentKey(): MacKey =
        runCatching { macKey(CURRENT_SECRET) }.getOrNull() ?: run {
            LOG.error("the cursor key secret '{}' is missing, blank or shorter than 32 bytes", CURRENT_SECRET)
            error("the sample check cursor key is not available")
        }

    private fun previousKey(): MacKey? = runCatching { macKey(PREVIOUS_SECRET) }.getOrNull()

    private fun macKey(path: String): MacKey {
        val text = secrets.read(SecretRef(path)).reveal()
        require(text.isNotBlank()) { "blank" }
        return MacKey(MacAlgorithm.HMAC_SHA_256, text.toByteArray(Charsets.UTF_8))
    }

    /** Strict base64url: the text must be the canonical encoding of what it decodes to. */
    private fun decoded(text: String): ByteArray? =
        runCatching { DECODER.decode(text) }.getOrNull()?.takeIf { ENCODER.encodeToString(it) == text }

    /** Length-prefixed fields: no value can shift into its neighbour. The issue time is not signed (freshness only). */
    private fun signed(
        entityRef: String,
        userId: String,
        position: SampleCheckPosition,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT,
            entityRef,
            userId,
            position.recordedAt.toString(),
            position.reference,
            position.hash,
        ).map { it.toByteArray(Charsets.UTF_8) }.forEach {
            data.writeInt(it.size)
            data.write(it)
        }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_SECRET = "evidence.sample-check-cursor-key"
        const val PREVIOUS_SECRET = "evidence.sample-check-cursor-key-previous"
        const val LAYOUT = "v1"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val PARTS = 3
        private const val FIELDS = 4
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(SampleCheckCursor::class.java)
    }
}
