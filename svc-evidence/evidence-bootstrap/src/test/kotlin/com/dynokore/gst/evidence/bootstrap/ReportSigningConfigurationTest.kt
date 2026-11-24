package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedVerification
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.ByteArrayInputStream
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey

/**
 * INV-AC-27741: the audit pack signing key reference is required, and the key is read per use (INV-T-27742). The
 * context cases start only [ReportSigningConfiguration] with a fake [SecretStore] and the system properties the Gradle
 * test task sets removed, so "absent" is really absent.
 */
class ReportSigningConfigurationTest {
    private val property = "evidence.audit-pack.signing-key-ref"

    private class FakeStore(
        var secret: String?,
    ) : SecretStore {
        var reads = 0

        override fun read(ref: SecretRef): Secret {
            reads++
            return secret?.let { Secret(it) } ?: throw SecretNotFound(ref)
        }
    }

    // not a nested @Configuration: the bootstrap package is component-scanned and would pick up a fake store
    private fun runner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withInitializer { it.environment.propertySources.remove("systemProperties") }
            .withBean(SecretStore::class.java, { FakeStore(P256_KEY + "\n" + P256_CERT) })
            .withUserConfiguration(ReportSigningConfiguration::class.java)

    private fun startFailure(vararg values: String): String {
        var failure: Throwable? = null
        runner().withPropertyValues(*values).run { failure = it.startupFailure }
        failure shouldNotBe null
        return generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private fun key(secret: String?) =
        FakeStore(secret).let {
            it to
                SecretSigningKey(it, SecretRef("audit-pack/signing-key"))
        }

    private fun pem(vararg blocks: String) = blocks.joinToString("\n")

    private fun derOf(certPem: String): ByteArray =
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray()))
            .encoded

    @Test
    fun `INV-AC-27741 property absent stops the start and the message names it`() {
        startFailure() shouldContain property
    }

    @Test
    fun `INV-AC-27741 blank property stops the start`() {
        startFailure("$property=") shouldContain property
    }

    @Test
    fun `INV-AC-27741 whitespace property stops the start`() {
        startFailure("$property= ") shouldContain property
    }

    @Test
    fun `INV-AC-27741 malformed reference stops the start`() {
        startFailure("$property=../other/secret") shouldContain property
    }

    @Test
    fun `INV-AC-27741 valid reference starts`() {
        runner().withPropertyValues("$property=audit-pack/signing-key").run {
            it.startupFailure shouldBe null
            it.getBean(ReportSigningProperties::class.java).keyRef shouldBe SecretRef("audit-pack/signing-key")
        }
    }

    @Test
    fun `INV-AC-27741 certificate equals the secret and the signature verifies`() {
        val (_, loader) = key(pem(P256_KEY, P256_CERT))
        loader.algorithm shouldBe SignatureAlgorithm.ECDSA_P256_SHA256
        loader.certificateDer.toList() shouldBe derOf(P256_CERT).toList()
        val cms = BouncyCastleCms()
        val content = "verification report".toByteArray()
        val signature = cms.sign(content, loader)
        cms
            .verify(content, signature, SignatureAlgorithm.ECDSA_P256_SHA256, listOf(derOf(P256_CERT)))
            .shouldBeInstanceOf<DetachedVerification.Verified>()
    }

    @Test
    fun `INV-AC-27741 the secret is read on every call and not kept`() {
        val (store, loader) = key(pem(P256_KEY, P256_CERT))
        loader.sign("a".toByteArray())
        loader.sign("b".toByteArray())
        store.reads shouldBe 2
        loader.certificateDer
        store.reads shouldBe 3
        // a rotated secret is used by the next call
        store.secret = pem(P256B_KEY, P256B_CERT)
        loader.certificateDer.toList() shouldBe derOf(P256B_CERT).toList()
    }

    @Test
    fun `INV-AC-27741 a P-384 key is refused`() {
        val (_, loader) = key(pem(P384_KEY, P384_CERT))
        shouldThrow<SigningKeyRefused> { loader.sign("x".toByteArray()) }.message!! shouldContain "P-256"
    }

    @Test
    fun `INV-AC-27741 a missing certificate is refused`() {
        val (_, loader) = key(pem(P256_KEY))
        shouldThrow<SigningKeyRefused> { loader.certificateDer }
    }

    @Test
    fun `INV-AC-27741 two keys are refused`() {
        val (_, loader) = key(pem(P256_KEY, P256B_KEY, P256_CERT))
        shouldThrow<SigningKeyRefused> { loader.sign("x".toByteArray()) }
    }

    @Test
    fun `INV-AC-27741 two certificates and no key are refused`() {
        shouldThrow<SigningKeyRefused> { key(pem(P256_CERT, P256B_CERT)).second.certificateDer }
    }

    @Test
    fun `INV-AC-27741 a missing secret is refused`() {
        val (_, loader) = key(null)
        shouldThrow<SigningKeyRefused> { loader.sign("x".toByteArray()) }.cause.shouldBeInstanceOf<SecretNotFound>()
    }

    @Test
    fun `INV-AC-27741 a refusal message carries no key text`() {
        val secrets =
            listOf(
                pem(P384_KEY, P384_CERT),
                pem(P256_KEY),
                pem(P256_KEY, P256B_KEY, P256_CERT),
                pem(P256_KEY.replace(Regex("(?<=\\n)[A-Za-z0-9+/=]"), "@"), P256_CERT),
                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n" + P256_CERT,
            )
        secrets.forEach { secret ->
            val failure = shouldThrow<SigningKeyRefused> { key(secret).second.sign("x".toByteArray()) }
            val body = secret.lines().filter { it.isNotBlank() && !it.startsWith("-----") }
            val text = generateSequence<Throwable>(failure) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            body.forEach { text shouldNotContain it }
            text shouldNotContain "BEGIN"
        }
    }

    @Test
    fun `INV-AC-27741 a corrupt block is refused`() {
        val (_, loader) = key("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n$P256_CERT")
        shouldThrow<SigningKeyRefused> { loader.sign("x".toByteArray()) }
    }

    @Test
    fun `INV-AC-28399 the test keys are generated at runtime and differ per pair`() {
        TestSigningMaterial.p256Key shouldNotBe TestSigningMaterial.p256bKey
        TestSigningMaterial.p256Key shouldNotBe TestSigningMaterial.p384Key
        (TestSigningMaterial.p256Pair.public as ECPublicKey).params.order.bitLength() shouldBe 256
        (TestSigningMaterial.p256bPair.public as ECPublicKey).params.order.bitLength() shouldBe 256
        (TestSigningMaterial.p384Pair.public as ECPublicKey).params.order.bitLength() shouldBe 384
        TestSigningMaterial.p256Certificate.verify(TestSigningMaterial.p256Pair.public)
        TestSigningMaterial.p256bCertificate.verify(TestSigningMaterial.p256bPair.public)
        TestSigningMaterial.p384Certificate.verify(TestSigningMaterial.p384Pair.public)
        shouldThrow<SignatureException> {
            TestSigningMaterial.p256Certificate.verify(TestSigningMaterial.p256bPair.public)
        }
    }

    private companion object {
        private val P256_KEY = TestSigningMaterial.p256Key
        private val P256_CERT = TestSigningMaterial.p256Cert
        private val P256B_KEY = TestSigningMaterial.p256bKey
        private val P256B_CERT = TestSigningMaterial.p256bCert
        private val P384_KEY = TestSigningMaterial.p384Key
        private val P384_CERT = TestSigningMaterial.p384Cert
    }
}
