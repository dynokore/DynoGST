package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.http.CursorBinding
import com.dynokore.gst.canonical.adapters.http.IngestedDocumentCursors
import com.dynokore.gst.canonical.application.exposure.ExposurePosition
import com.dynokore.platform.crypto.MacAlgorithm
import com.dynokore.platform.crypto.MacKey
import com.dynokore.platform.crypto.Macs
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * `v1.` + base64url(issue time, position) + `.` + base64url(tag), the tag a HMAC-SHA256 of `Macs` (CS-53) over the
 * layout, the tenant, the acting user ID (never a role), the three filters, the sort and the payload (API-18). Only
 * the payload is in the token. A token is accepted for [MAX_AGE] after its issue. The key is read through
 * [SecretStore] at every use, so a rotated secret needs no restart; the previous key only verifies, so a rotation
 * does not end a cursor early.
 */
class SignedIngestedDocumentCursors(
    private val secrets: SecretStore,
    private val clock: Clock,
) : IngestedDocumentCursors {
    override fun issue(
        binding: CursorBinding,
        last: ExposurePosition,
    ): String {
        // sourceSystem comes last: it is the one field that may hold the separator
        val payload =
            listOf(clock.instant(), last.recordedAt, last.docUuid, last.version, last.sourceSystem)
                .joinToString(SEPARATOR)
                .toByteArray(Charsets.UTF_8)
        val tag = Macs.tag(currentKey(), signed(binding, payload))
        return "$LAYOUT.${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(tag)}"
    }

    override fun open(
        token: String,
        binding: CursorBinding,
    ): ExposurePosition? {
        val parts = token.split('.')
        val payload = if (parts.size == PARTS && parts[0] == LAYOUT) decoded(parts[1]) else null
        val tag = if (payload != null) decoded(parts[2]) else null
        return if (payload != null && tag != null && genuine(signed(binding, payload), tag)) {
            positionOf(payload)
        } else {
            null
        }
    }

    private fun genuine(
        data: ByteArray,
        tag: ByteArray,
    ): Boolean = listOfNotNull(currentKey(), previousKey()).any { Macs.verify(it, data, tag) }

    /** The position of a genuine [payload] that has not expired. */
    private fun positionOf(payload: ByteArray): ExposurePosition? {
        val fields = String(payload, Charsets.UTF_8).split(SEPARATOR, limit = FIELDS)
        val now = clock.instant()
        val issuedAt = Instant.parse(fields[ISSUED])
        val live = !issuedAt.isAfter(now) && !issuedAt.plus(MAX_AGE).isBefore(now)
        return if (live) {
            ExposurePosition(
                sourceSystem = fields[SOURCE_SYSTEM],
                recordedAt = Instant.parse(fields[RECORDED_AT]),
                docUuid = UUID.fromString(fields[DOC_UUID]),
                version = fields[VERSION].toInt(),
            )
        } else {
            null
        }
    }

    private fun currentKey(): MacKey = macKey(CURRENT_SECRET)

    private fun previousKey(): MacKey? =
        runCatching { macKey(PREVIOUS_SECRET) }
            .onFailure { LOG.debug("no usable previous cursor key under '{}'", PREVIOUS_SECRET) }
            .getOrNull()

    /** A missing or short key throws: the request fails, and no page is signed with a weak key. */
    private fun macKey(path: String): MacKey {
        val text = secrets.read(SecretRef(path)).reveal()
        require(text.isNotBlank()) { "the cursor key secret '$path' is blank" }
        return MacKey(MacAlgorithm.HMAC_SHA_256, text.toByteArray(Charsets.UTF_8))
    }

    /** Strict base64url: the text must be the canonical encoding of what it decodes to. */
    private fun decoded(text: String): ByteArray? =
        runCatching { DECODER.decode(text) }.getOrNull()?.takeIf { ENCODER.encodeToString(it) == text }

    /** Length-prefixed fields: no value can shift into its neighbour. */
    private fun signed(
        binding: CursorBinding,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT,
            binding.tenant,
            binding.userId,
            binding.window.principalRef,
            binding.window.recordedFrom.toString(),
            binding.window.recordedTo.toString(),
            SORT,
        ).map { it.toByteArray(Charsets.UTF_8) }
            .plus(payload)
            .forEach {
                data.writeInt(it.size)
                data.write(it)
            }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_SECRET = "canonical/ingested-documents-cursor-key"
        const val PREVIOUS_SECRET = "canonical/ingested-documents-cursor-key-previous"
        const val LAYOUT = "v1"
        const val SORT = "sourceSystem:asc,recordedAt:asc,docUuid:asc,version:asc"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val SEPARATOR = "\n"
        private const val PARTS = 3
        private const val ISSUED = 0
        private const val RECORDED_AT = 1
        private const val DOC_UUID = 2
        private const val VERSION = 3
        private const val SOURCE_SYSTEM = 4
        private const val FIELDS = 5
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(SignedIngestedDocumentCursors::class.java)
    }
}
