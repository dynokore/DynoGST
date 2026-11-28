package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.certification.CertificationHistoryPosition
import com.dynokore.platform.crypto.MacAlgorithm
import com.dynokore.platform.crypto.MacKey
import com.dynokore.platform.crypto.Macs
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The cursor of `listSandboxCertifications` (API-18): `sc1.` + base64url(recordedAt, issue time, sequence) + `.` +
 * base64url(tag), the tag a HMAC-SHA256 of `Macs` (CS-53) over the layout, the entityRef of the path, the acting user
 * ID (never a role), the sort key, the filter key and the payload. The layout prefix differs from the other cursors, so
 * a token of another list is refused. The key is the cursor key secret of the service, read through [SecretStore] at
 * every use; the previous key is accepted for verification only. A token is valid for 24 hours.
 */
class SandboxCertificationCursor(
    private val secrets: SecretStore,
) {
    /** The token for the page after [last]. A missing, blank or short current key is a 5xx, never an unsigned page. */
    fun issue(
        entityRef: String,
        userId: String,
        last: CertificationHistoryPosition,
        now: Instant,
    ): String {
        val payload = "${last.recordedAt}\n${now.toEpochMilli()}\n${last.sequence}".toByteArray(Charsets.UTF_8)
        val tag = Macs.tag(currentKey(), signed(entityRef, userId, payload))
        return "$LAYOUT.${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(tag)}"
    }

    /** The position the token names, or a 400 that does not say which check failed (SRT-001). */
    fun open(
        token: String,
        entityRef: String,
        userId: String,
        now: Instant,
    ): CertificationHistoryPosition {
        val parts = token.split('.')
        val payload = if (parts.size == PARTS && parts[0] == LAYOUT) decoded(parts[1]) else null
        val tag = if (payload != null) decoded(parts[2]) else null
        val key = currentKey()
        val genuine =
            payload != null && tag != null &&
                keysToTry(key).any { Macs.verify(it, signed(entityRef, userId, payload), tag) }
        val position = if (genuine) positionOf(requireNotNull(payload), now) else null
        return position ?: invalid(REFUSED)
    }

    private fun positionOf(
        payload: ByteArray,
        now: Instant,
    ): CertificationHistoryPosition? {
        val fields = String(payload, Charsets.UTF_8).split('\n', limit = FIELDS)
        val at = fields.getOrNull(0)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val issued = fields.getOrNull(1)?.toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        val sequence = fields.getOrNull(2)?.toLongOrNull()
        val fresh = issued != null && !issued.isAfter(now) && !issued.plus(MAX_AGE).isBefore(now)
        return if (at != null && sequence != null && fresh) CertificationHistoryPosition(at, sequence) else null
    }

    private fun keysToTry(current: MacKey): List<MacKey> = listOfNotNull(current, previousKey())

    private fun currentKey(): MacKey =
        runCatching { macKey(CURRENT_SECRET) }.getOrNull() ?: run {
            LOG.error("the cursor key secret '{}' is missing, blank or shorter than 32 bytes", CURRENT_SECRET)
            throw ApiRefusal(ClosedErrorCode.INTERNAL_ERROR)
        }

    private fun previousKey(): MacKey? = runCatching { macKey(PREVIOUS_SECRET) }.getOrNull()

    private fun macKey(path: String): MacKey {
        val text =
            try {
                secrets.read(SecretRef(path)).reveal()
            } catch (_: SecretNotFound) {
                error("no secret")
            }
        require(text.isNotBlank()) { "blank" }
        return MacKey(MacAlgorithm.HMAC_SHA_256, text.toByteArray(Charsets.UTF_8))
    }

    /** Strict base64url: the text must be the canonical encoding of what it decodes to. */
    private fun decoded(text: String): ByteArray? =
        runCatching { DECODER.decode(text) }.getOrNull()?.takeIf { ENCODER.encodeToString(it) == text }

    /** Length-prefixed fields: no value can shift into its neighbour. */
    private fun signed(
        entityRef: String,
        userId: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT.toByteArray(),
            entityRef.toByteArray(),
            userId.toByteArray(),
            SORT.toByteArray(),
            FILTER.toByteArray(),
            payload,
        ).forEach {
            data.writeInt(it.size)
            data.write(it)
        }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_SECRET = PeriodListCursor.CURRENT_SECRET
        const val PREVIOUS_SECRET = PeriodListCursor.PREVIOUS_SECRET
        const val LAYOUT = "sc1"
        const val SORT = "recordedAt:asc,sequence:asc"
        const val FILTER = "none"
        const val REFUSED = "cursor is not valid"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val PARTS = 3
        private const val FIELDS = 3
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(SandboxCertificationCursor::class.java)
    }
}
