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
import java.security.cert.CertificateFactory

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
                pem(P256_KEY.replace("MI", "@@"), P256_CERT),
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

    private companion object {
        private val P256_KEY =
            listOf(
                "-----BEGIN PRIVATE KEY-----",
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgZrqp+p2dyYyFZ8S0",
                "GiIvAWgzBo0p2DnLY/oyexfHgwChRANCAAQdVDXlLE0CKSYRwgJNWfjcHCkwyD/2",
                "iTfrnIyUd/jmmYn31w7QrNq+rjmWXjWtJKGsyB06mDU5jMvn9LOIB99s",
                "-----END PRIVATE KEY-----",
            ).joinToString("\n")

        private val P256_CERT =
            listOf(
                "-----BEGIN CERTIFICATE-----",
                "MIIBmDCCAT2gAwIBAgIUZfd/11smjHdJ1LcAI8Lx67Z+lMowCgYIKoZIzj0EAwIw",
                "IDEeMBwGA1UEAwwVZXZpZGVuY2Utc2lnbmluZy10ZXN0MCAXDTI2MTEyMzEzNTYw",
                "NloYDzIxMjYxMDMwMTM1NjA2WjAgMR4wHAYDVQQDDBVldmlkZW5jZS1zaWduaW5n",
                "LXRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQdVDXlLE0CKSYRwgJNWfjc",
                "HCkwyD/2iTfrnIyUd/jmmYn31w7QrNq+rjmWXjWtJKGsyB06mDU5jMvn9LOIB99s",
                "o1MwUTAdBgNVHQ4EFgQU8Ym2CTFxzgN6uN/fElVgyrLDPkUwHwYDVR0jBBgwFoAU",
                "8Ym2CTFxzgN6uN/fElVgyrLDPkUwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD",
                "AgNJADBGAiEAwxn7lagv77QARjz6sgkjpy6TW0gBU4+2EKLgIqf0pM0CIQC9iO+I",
                "foyyA4RSc+eq6PLJY2QxY9dR/Be5JzGpcFXnkw==",
                "-----END CERTIFICATE-----",
            ).joinToString("\n")

        private val P256B_KEY =
            listOf(
                "-----BEGIN PRIVATE KEY-----",
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgtb9JT9luVCus8mzP",
                "LYWr1A1+7MEvo0kNOTGiNwyyc9ihRANCAATDD+IFpk+C1krywT2wRYBEqjgid1mc",
                "0finYNDWQlEq5qdHPA0wtfW266L6PgpDF5QMSKcZZT2uIZHNLqDNSIWh",
                "-----END PRIVATE KEY-----",
            ).joinToString("\n")

        private val P256B_CERT =
            listOf(
                "-----BEGIN CERTIFICATE-----",
                "MIIBljCCAT2gAwIBAgIUa9opyeniDLsSpoq7+QuH1hAeX/cwCgYIKoZIzj0EAwIw",
                "IDEeMBwGA1UEAwwVZXZpZGVuY2Utc2lnbmluZy10ZXN0MCAXDTI2MTEyMzEzNTYw",
                "NloYDzIxMjYxMDMwMTM1NjA2WjAgMR4wHAYDVQQDDBVldmlkZW5jZS1zaWduaW5n",
                "LXRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATDD+IFpk+C1krywT2wRYBE",
                "qjgid1mc0finYNDWQlEq5qdHPA0wtfW266L6PgpDF5QMSKcZZT2uIZHNLqDNSIWh",
                "o1MwUTAdBgNVHQ4EFgQUpj66WxssjGP6re+JQe/iGjDELPowHwYDVR0jBBgwFoAU",
                "pj66WxssjGP6re+JQe/iGjDELPowDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD",
                "AgNHADBEAiBEqZ8NQhXcjfFtkeb9uXnSZ4qnIMI92AaodTGMBG2qagIgfNMQS9EI",
                "se99abls1YWBnYHBAzO5AIygGhsfee+0PtA=",
                "-----END CERTIFICATE-----",
            ).joinToString("\n")

        private val P384_KEY =
            listOf(
                "-----BEGIN PRIVATE KEY-----",
                "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDAa8gdHuqRvXVvLDRpA",
                "n5Qo1SlOVAH52paf804mMKzlcV94e0mCoVYUKaxOvjc99x6hZANiAARqfBAUJZKD",
                "g8RZKSq/LPxDnzZ97DEWmNWCQSbWXb85O3wBZdd9Wi95thc8vjD0yyHrdvZ0hqtU",
                "t2BiozZRUgW9t370fsm71smlzyXEgHGlLnEtBylAqo69YUNq7yYPSto=",
                "-----END PRIVATE KEY-----",
            ).joinToString("\n")

        private val P384_CERT =
            listOf(
                "-----BEGIN CERTIFICATE-----",
                "MIIB1TCCAVqgAwIBAgIUfPFjTbVb4t9YnKfuLbBkysam6aYwCgYIKoZIzj0EAwIw",
                "IDEeMBwGA1UEAwwVZXZpZGVuY2Utc2lnbmluZy10ZXN0MCAXDTI2MTEyMzEzNTYw",
                "NloYDzIxMjYxMDMwMTM1NjA2WjAgMR4wHAYDVQQDDBVldmlkZW5jZS1zaWduaW5n",
                "LXRlc3QwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAARqfBAUJZKDg8RZKSq/LPxDnzZ9",
                "7DEWmNWCQSbWXb85O3wBZdd9Wi95thc8vjD0yyHrdvZ0hqtUt2BiozZRUgW9t370",
                "fsm71smlzyXEgHGlLnEtBylAqo69YUNq7yYPStqjUzBRMB0GA1UdDgQWBBQCdKyK",
                "qUwPTEkEcWnYeODSTxM2ETAfBgNVHSMEGDAWgBQCdKyKqUwPTEkEcWnYeODSTxM2",
                "ETAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA2kAMGYCMQCQcEbhkd9gefOZ",
                "9+dgTCZ+3SX5VLBwiGdqoalLYIDMbiMGE3B0/xfssK4iBK5WGtMCMQCX/JRvoLIR",
                "Oeljm9Nd2oTHMgN85lZ0OpkjPyfZ/iwnel5768DpMJCTt3un1ejTUoI=",
                "-----END CERTIFICATE-----",
            ).joinToString("\n")
    }
}
