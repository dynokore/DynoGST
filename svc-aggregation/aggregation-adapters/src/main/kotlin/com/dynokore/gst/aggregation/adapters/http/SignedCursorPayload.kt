package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The integrity protection the page cursors of aggregation-svc share (API-18): the fields, each base64url, and an
 * HMAC-SHA256 tag over them, both base64url. The key [DRILL_DOWN_CURSOR_KEY_SECRET] is read through [SecretStore] at
 * every use (STK-29), so a rotated secret needs no restart; when it cannot be read [SecretNotFound] propagates and the
 * controller answers 500 (fail closed, no default key). Every cursor kind puts its own version label in the first
 * field and checks it, so a cursor of one operation is never read as that of another.
 */
internal class SignedCursorPayload(
    private val secrets: SecretStore,
) {
    fun seal(fields: List<String>): String {
        val body =
            fields
                .joinToString(
                    SEPARATOR,
                ) { ENCODER.encodeToString(it.toByteArray(Charsets.UTF_8)) }
                .toByteArray()
        return "${ENCODER.encodeToString(body)}.${ENCODER.encodeToString(mac(body))}"
    }

    /** The decoded fields when the tag is the HMAC of the payload, checked before anything in it is read; else null. */
    fun open(cursor: String): List<String>? {
        val parts = cursor.split('.')
        val body = parts.takeIf { it.size == 2 }?.let { decoded(it[0]) }
        val tag = parts.getOrNull(1)?.let { decoded(it) }
        val genuine = body != null && tag != null && MessageDigest.isEqual(mac(body), tag)
        val fields = if (genuine) String(checkNotNull(body), Charsets.UTF_8).split(SEPARATOR).map(::text) else null
        return fields?.takeIf { f -> f.none { it == null } }?.filterNotNull()
    }

    private fun text(field: String): String? = decoded(field)?.toString(Charsets.UTF_8)

    private fun decoded(text: String): ByteArray? = runCatching { DECODER.decode(text) }.getOrNull()

    private fun mac(body: ByteArray): ByteArray {
        val key = secrets.read(SecretRef(DRILL_DOWN_CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(body)
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val SEPARATOR = ":"
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
