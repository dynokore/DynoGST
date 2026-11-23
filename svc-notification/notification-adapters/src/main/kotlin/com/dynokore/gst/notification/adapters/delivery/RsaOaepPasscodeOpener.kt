package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.gst.notification.application.delivery.PasscodeOpener
import com.dynokore.gst.notification.application.delivery.PasscodeUnopenable
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Opens the passcode string sealed by access-svc (INV-REQ-2762): `v1.<keyId>.<ciphertext>`, RSAES-OAEP with SHA-256
 * and MGF1-SHA-256, the label being the UTF-8 bytes of the recipient address. The private key is read through the
 * SecretStore binding. Every failure throws [PasscodeUnopenable] with no cause and no value; the clear passcode is
 * returned to the caller and held nowhere here.
 */
class RsaOaepPasscodeOpener(
    private val secrets: SecretStore,
) : PasscodeOpener {
    /** The parsed key and its key id; held after a successful read only, never the clear passcode. */
    private class OpeningKey(
        val key: RSAPrivateCrtKey,
        val keyId: String,
    )

    @Volatile
    private var opening: OpeningKey? = null

    override fun open(
        sealed: String,
        label: String,
    ): String {
        try {
            val match = checkNotNull(FORM.matchEntire(sealed))
            val keyId = match.groupValues[1]
            val ciphertext = Base64.getUrlDecoder().decode(match.groupValues[2])
            val held = opening ?: load().also { opening = it }
            check(held.keyId == keyId)
            val spec =
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified(label.toByteArray(Charsets.UTF_8)),
                )
            // "ECB" is only the JCA name for single-block RSA (no block chaining); the padding is randomised OAEP.
            // The JDK accepts no other mode token for RSA, so the generic ECB rule is a false positive here.
            // nosemgrep: kotlin.lang.security.ecb-cipher.ecb-cipher
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding", "SunJCE")
            cipher.init(Cipher.DECRYPT_MODE, held.key, spec)
            val clear = cipher.doFinal(ciphertext)
            return Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(clear))
                .toString()
        } catch (_: Exception) {
            throw PasscodeUnopenable()
        }
    }

    private fun load(): OpeningKey {
        val pem = secrets.read(SecretRef(SECRET_REF)).reveal()
        val body = pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").replace(WHITESPACE, "")
        require(PEM_HEADER in pem && PEM_FOOTER in pem)
        val factory = KeyFactory.getInstance("RSA")
        val key = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        check(key is RSAPrivateCrtKey && key.modulus.bitLength() >= MIN_BITS)
        val publicKey = factory.generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent))
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        val keyId = digest.take(KEY_ID_BYTES).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        return OpeningKey(key, keyId)
    }

    companion object {
        const val SECRET_REF = "message-delivery-sealing-private-key"
        const val MIN_BITS = 3072
        private const val KEY_ID_BYTES = 8
        private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_FOOTER = "-----END PRIVATE KEY-----"
        private val WHITESPACE = Regex("\\s")
        private val FORM = Regex("^v1\\.([0-9a-f]{16})\\.([A-Za-z0-9_-]{512}|[A-Za-z0-9_-]{683})$")
    }
}
