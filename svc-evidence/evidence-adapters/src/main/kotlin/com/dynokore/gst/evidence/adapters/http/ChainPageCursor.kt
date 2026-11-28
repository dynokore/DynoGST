package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.lookup.ChainCursor
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.platform.crypto.MacAlgorithm
import com.dynokore.platform.crypto.MacKey
import com.dynokore.platform.crypto.Macs
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The page cursor of `getAcknowledgementChain` (INV-AC-29278): `chain-v1.` + base64url(position, issue time) + `.` +
 * base64url(tag), the tag a HMAC-SHA256 of `Macs` (CS-53) over the layout, the tenant id, the acting user ID (never a
 * role), the Acknowledgement ID, the position and the issue time.
 * The tenant, the user ID and the key are not in the token. The key is read through [SecretStore] at every use, never
 * cached; the previous key is accepted for verification only. Every refusal answers null alike.
 */
class ChainPageCursor(
    private val secrets: SecretStore,
) {
    /** A missing, blank or short current key throws: a 5xx, never an unsigned cursor. */
    fun issue(
        tenant: TenantId,
        userId: String,
        ackId: String,
        last: ChainCursor,
        now: Instant,
    ): String {
        val issued = now.toEpochMilli()
        val tag = Macs.tag(currentKey(), signed(tenant, userId, ackId, last, issued))
        return "$LAYOUT.${ENCODER.encodeToString(payloadOf(last, issued))}.${ENCODER.encodeToString(tag)}"
    }

    /** The signed position, or null. A missing, blank or short current key throws, whatever the token holds. */
    fun open(
        token: String,
        tenant: TenantId,
        userId: String,
        ackId: String,
        now: Instant,
    ): ChainCursor? {
        val key = currentKey()
        val parts = token.split('.')
        val shaped = parts.size == PARTS && parts[0] == LAYOUT
        val parsed = if (shaped) decoded(parts[1])?.let { parse(it) } else null
        val tag = if (shaped) decoded(parts[2]) else null
        val genuine =
            parsed != null && tag != null && fresh(parsed.second, now) &&
                listOfNotNull(key, previousKey()).any {
                    Macs.verify(it, signed(tenant, userId, ackId, parsed.first, parsed.second), tag)
                }
        return if (genuine) parsed.first else null
    }

    /** Valid from the issue time to [MAX_AGE] after it, both ends included, in whole milliseconds. */
    private fun fresh(
        issuedAtMillis: Long,
        now: Instant,
    ): Boolean = issuedAtMillis in (now.toEpochMilli() - MAX_AGE.toMillis())..now.toEpochMilli()

    private fun payloadOf(
        last: ChainCursor,
        issuedAtMillis: Long,
    ): ByteArray = "${last.appliedAt}\n${last.kind.name}\n${last.seq}\n$issuedAtMillis".toByteArray(Charsets.UTF_8)

    /** The payload must be the exact bytes [payloadOf] writes: no second spelling of a signed value is accepted. */
    private fun parse(payload: ByteArray): Pair<ChainCursor, Long>? =
        runCatching {
            val fields = String(payload, Charsets.UTF_8).split('\n')
            val position =
                ChainCursor(Instant.parse(fields[0]), ChainItemKind.valueOf(fields[1]), fields[2].toLong())
            position to fields[ISSUED].toLong()
        }.getOrNull()?.takeIf { payloadOf(it.first, it.second).contentEquals(payload) }

    private fun currentKey(): MacKey =
        runCatching { macKey(CURRENT_SECRET) }.getOrNull() ?: run {
            LOG.error("the cursor key secret '{}' is missing, blank or shorter than 32 bytes", CURRENT_SECRET)
            error("the acknowledgement chain cursor key is not available")
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

    /** Length-prefixed fields: no value shifts into its neighbour. The issue time is signed (expiry is unforgeable). */
    private fun signed(
        tenant: TenantId,
        userId: String,
        ackId: String,
        position: ChainCursor,
        issuedAtMillis: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT,
            tenant.value.toString(),
            userId,
            ackId,
            position.appliedAt.toString(),
            position.kind.name,
            position.seq.toString(),
            issuedAtMillis.toString(),
        ).map { it.toByteArray(Charsets.UTF_8) }.forEach {
            data.writeInt(it.size)
            data.write(it)
        }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_SECRET = "evidence.chain-cursor-key"
        const val PREVIOUS_SECRET = "evidence.chain-cursor-key-previous"
        const val LAYOUT = "chain-v1"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val PARTS = 3
        private const val ISSUED = 3
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(ChainPageCursor::class.java)
    }
}
