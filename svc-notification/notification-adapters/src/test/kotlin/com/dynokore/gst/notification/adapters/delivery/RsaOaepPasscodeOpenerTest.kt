package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.gst.notification.application.delivery.PasscodeUnopenable
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class RsaOaepPasscodeOpenerTest {
    private val label = "a@x.test"
    private val passcode = "482913"

    private class FakeStore(
        var value: () -> String,
    ) : SecretStore {
        var reads = 0

        override fun read(ref: SecretRef): Secret {
            assertEquals(RsaOaepPasscodeOpener.SECRET_REF, ref.value)
            reads++
            return Secret(value())
        }
    }

    private fun pair(bits: Int = 3072): KeyPair =
        KeyPairGenerator.getInstance("RSA").also { it.initialize(bits) }.generateKeyPair()

    private fun pem(pair: KeyPair): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

    private fun keyId(pair: KeyPair): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(pair.public.encoded)
            .take(8)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    // A copy of the access sealer's JDK calls.
    private fun seal(
        pair: KeyPair,
        text: String = passcode,
        forLabel: String = label,
    ): String {
        val spec =
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(forLabel.toByteArray()))
        // nosemgrep: kotlin.lang.security.ecb-cipher.ecb-cipher
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pair.public, spec)
        val ct = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(text.toByteArray()))
        return "v1.${keyId(pair)}.$ct"
    }

    private val main = pair()
    private val other = pair()

    private fun opener(store: FakeStore = FakeStore { pem(main) }) = RsaOaepPasscodeOpener(store)

    private fun refused(
        sealed: String,
        forLabel: String = label,
        opener: RsaOaepPasscodeOpener = opener(),
    ): PasscodeUnopenable {
        val e = assertThrows(PasscodeUnopenable::class.java) { opener.open(sealed, forLabel) }
        assertEquals(PasscodeUnopenable.MESSAGE, e.message)
        assertNull(e.cause)
        return e
    }

    @Test
    fun `INV-AC-26536 opens a string sealed by the form's algorithm for the same label`() {
        opener().open(seal(main), label) shouldBe passcode
    }

    @Test
    fun `INV-AC-26536 another label is refused`() {
        refused(seal(main), "b@x.test")
        refused(seal(main), "")
    }

    @Test
    fun `INV-AC-26536 a string sealed under another key id is refused without a decrypt attempt`() {
        refused(seal(other))
        // The ciphertext is valid for the held key; only the key id names another key. A decrypt attempt would
        // succeed, so the refusal can come from the key id check alone.
        val ct = seal(main).substringAfterLast('.')
        refused("v1.${keyId(other)}.$ct")
        opener().open("v1.${keyId(main)}.$ct", label) shouldBe passcode
    }

    @Test
    @DisplayName(
        "INV-AC-26536 v2, upper-case key id, padded ciphertext, 511-character ciphertext and " +
            "a flipped ciphertext character are each refused",
    )
    fun malformedFormsAreRefused() {
        val good = seal(main)
        val (id, ct) = good.removePrefix("v1.").split(".")
        refused("v2.$id.$ct")
        refused("v1.${id.uppercase()}.$ct".takeIf { it != "v1.$id.$ct" } ?: "v1.ABCDEF0123456789.$ct")
        refused("v1.ABCDEF0123456789.$ct")
        refused("v1.$id.$ct=")
        refused("v1.$id.${ct.dropLast(1)}")
        val flipped = (if (ct[10] == 'A') 'B' else 'A')
        refused("v1.$id.${ct.substring(0, 10)}$flipped${ct.substring(11)}")
    }

    @Test
    fun `INV-AC-26536 a missing, unreadable, non-PKCS#8, EC or 2048-bit secret is refused and a later call succeeds`() {
        val sealed = seal(main)
        val ec = KeyPairGenerator.getInstance("EC").also { it.initialize(256) }.generateKeyPair()
        val failing: List<() -> String> =
            listOf(
                { throw SecretNotFound(SecretRef(RsaOaepPasscodeOpener.SECRET_REF)) },
                { throw IllegalStateException("unreadable $passcode") },
                { "not a key" },
                { pem(main).replace("PRIVATE KEY", "RSA PRIVATE KEY") },
                { pem(ec) },
                { pem(pair(2048)) },
            )
        for (bad in failing) {
            val store = FakeStore(bad)
            val opener = opener(store)
            refused(sealed, opener = opener)
            store.value = { pem(main) }
            opener.open(sealed, label) shouldBe passcode
            assertEquals(2, store.reads)
        }
    }

    @Test
    fun `INV-AC-26536 no exception text contains the sealed string, the label or the clear passcode and no cause`() {
        val sealed = seal(main)
        val e = refused(sealed, "b@x.test")
        for (secret in listOf(sealed, "b@x.test", label, passcode)) {
            assertFalse(e.toString().contains(secret))
            assertFalse(e.stackTraceToString().contains(secret))
        }
        assertEquals(0, e.suppressed.size)
        val e2 = refused("v2.x.y", opener = opener(FakeStore { throw IllegalStateException(passcode) }))
        assertFalse(e2.stackTraceToString().contains(passcode))
    }

    @Test
    fun `INV-AC-26536 the opener has no field holding a clear passcode`() {
        val o = opener()
        o.open(seal(main), label) shouldBe passcode
        val fields =
            RsaOaepPasscodeOpener::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        for (f in fields) {
            f.isAccessible = true
            val v = f.get(o)
            assertFalse(v is String || v is CharArray || v is ByteArray, "field ${f.name}")
            assertFalse(v.toString().contains(passcode))
        }
        val held = fields.first { it.name == "opening" }.also { it.isAccessible = true }.get(o)!!
        for (f in held.javaClass.declaredFields) {
            f.isAccessible = true
            val v = f.get(held)
            assertTrue(v !is CharArray && v !is ByteArray)
            assertFalse(v.toString().contains(passcode))
        }
    }
}
