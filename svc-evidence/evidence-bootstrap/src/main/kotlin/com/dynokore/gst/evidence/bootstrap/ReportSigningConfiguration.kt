package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.application.export.ReportSigner
import com.dynokore.gst.evidence.application.export.SignedReport
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * `evidence.audit-pack.signing-key-ref`: the installation evidence-signing key, held as a key reference (INV-REQ-663,
 * STK-29). Set per installation by the Helm values; there is no default, so a service started without it does not
 * start.
 */
@ConfigurationProperties(prefix = "evidence.audit-pack")
class ReportSigningProperties(
    signingKeyRef: String?,
) {
    /** Never null or blank, and always a valid [SecretRef]: anything else stops the binding (fail closed). */
    val keyRef: SecretRef = parse(signingKeyRef)

    private companion object {
        const val PROPERTY = "evidence.audit-pack.signing-key-ref"

        fun parse(text: String?): SecretRef {
            check(!text.isNullOrBlank()) { "$PROPERTY is required and must not be blank" }
            return try {
                SecretRef(text)
            } catch (e: IllegalArgumentException) {
                // the reference names a mount path, never key text; e.message repeats only that reference
                throw IllegalStateException("$PROPERTY is not a valid secret reference: ${e.message}", e)
            }
        }
    }
}

/**
 * A [SigningKey] whose key and certificate sit in one secret: one PKCS#8 `PRIVATE KEY` block (EC P-256) and one
 * `CERTIFICATE` block in PEM. The secret is read on each [sign] and each [certificateDer] call and parsed for that call
 * only: nothing of it is kept in a field (STK-29) and no refusal message carries a byte of it.
 */
class SecretSigningKey(
    private val store: SecretStore,
    private val ref: SecretRef,
) : SigningKey {
    override val algorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_P256_SHA256

    override val certificateDer: ByteArray get() = load().certificateDer

    override fun sign(data: ByteArray): ByteArray = load().sign(data)

    override fun toString(): String = "SecretSigningKey($ref)"

    private fun load(): JcaSigningKey {
        val pem = readSecret()
        val keys = blocks(pem, "PRIVATE KEY")
        val certs = blocks(pem, "CERTIFICATE")
        if (keys.size != 1 || certs.size != 1) {
            throw SigningKeyRefused(
                "signing key secret '$ref' must hold exactly one PRIVATE KEY and one CERTIFICATE block " +
                    "(found ${keys.size} and ${certs.size})",
            )
        }
        return JcaSigningKey(algorithm, privateKey(keys.single()), certs.single())
    }

    private fun readSecret(): String =
        try {
            store.read(ref).reveal()
        } catch (e: SecretNotFound) {
            throw SigningKeyRefused("signing key secret '$ref' is not bound", e)
        }

    // the parser's exception is dropped on purpose: its message can echo bytes of the key (no secret byte in a refusal)
    @Suppress("SwallowedException")
    private fun privateKey(der: ByteArray): ECPrivateKey {
        val key =
            try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as? ECPrivateKey
            } catch (e: GeneralSecurityException) {
                throw SigningKeyRefused("signing key secret '$ref' does not hold a PKCS#8 EC private key")
            }
        return key?.takeIf { isP256(it) }
            ?: throw SigningKeyRefused("signing key secret '$ref' does not hold an EC P-256 private key")
    }

    private fun isP256(key: ECPrivateKey): Boolean {
        val p256 =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        val actual = key.params
        return actual.curve == p256.curve && actual.generator == p256.generator && actual.order == p256.order
    }

    @Suppress("SwallowedException") // the decoder's message can echo a character of the secret
    private fun blocks(
        pem: String,
        label: String,
    ): List<ByteArray> =
        Regex("-----BEGIN $label-----([A-Za-z0-9+/=\\s]*?)-----END $label-----")
            .findAll(pem)
            .map { m ->
                try {
                    Base64.getMimeDecoder().decode(m.groupValues[1])
                } catch (e: IllegalArgumentException) {
                    throw SigningKeyRefused("signing key secret '$ref' holds a $label block that is not Base64")
                }
            }.toList()
}

/** A signing-key secret that cannot be used. The message names the reference only, never a secret byte. */
class SigningKeyRefused(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The report signing key, read from its secret on every use; the audit pack report is signed with it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportSigningProperties::class)
class ReportSigningConfiguration {
    @Bean
    fun reportSigningKey(
        properties: ReportSigningProperties,
        store: SecretStore,
    ): SigningKey = SecretSigningKey(store, properties.keyRef)

    /** Signs the report with the key of [reportSigningKey] and ships its certificate (STK-13: detached CMS). */
    @Bean
    fun reportSigner(reportSigningKey: SigningKey): ReportSigner {
        val signer: DetachedSigner = BouncyCastleCms()
        return ReportSigner { report ->
            SignedReport(signer.sign(report, reportSigningKey), certificatePem(reportSigningKey.certificateDer))
        }
    }

    private fun certificatePem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE-----\n"

    private companion object {
        const val PEM_LINE = 64
    }
}
