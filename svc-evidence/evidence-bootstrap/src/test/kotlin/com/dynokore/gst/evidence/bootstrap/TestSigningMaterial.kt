package com.dynokore.gst.evidence.bootstrap

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Test-only signing material (OWASP ASVS V6, CWE-798): every key pair and self-signed certificate is generated when the
 * test class first touches it, so no private key is stored in source. Three pairs: two distinct EC P-256 pairs and one
 * EC P-384 pair.
 */
internal object TestSigningMaterial {
    private const val SUBJECT = "CN=evidence-signing-test"

    private fun generate(curve: String): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair()

    private fun certificateOf(
        pair: KeyPair,
        signatureAlgorithm: String,
    ): X509Certificate {
        val now = Instant.now()
        val builder =
            JcaX509v3CertificateBuilder(
                X500Name(SUBJECT),
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(1))),
                X500Name(SUBJECT),
                pair.public,
            )
        val signer = JcaContentSignerBuilder(signatureAlgorithm).build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun pem(
        label: String,
        der: ByteArray,
    ): String =
        "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $label-----"

    val p256Pair: KeyPair by lazy { generate("secp256r1") }
    val p256bPair: KeyPair by lazy { generate("secp256r1") }
    val p384Pair: KeyPair by lazy { generate("secp384r1") }

    val p256Certificate: X509Certificate by lazy { certificateOf(p256Pair, "SHA256withECDSA") }
    val p256bCertificate: X509Certificate by lazy { certificateOf(p256bPair, "SHA256withECDSA") }
    val p384Certificate: X509Certificate by lazy { certificateOf(p384Pair, "SHA384withECDSA") }

    val p256Key: String by lazy { pem("PRIVATE KEY", p256Pair.private.encoded) }
    val p256bKey: String by lazy { pem("PRIVATE KEY", p256bPair.private.encoded) }
    val p384Key: String by lazy { pem("PRIVATE KEY", p384Pair.private.encoded) }

    val p256Cert: String by lazy { pem("CERTIFICATE", p256Certificate.encoded) }
    val p256bCert: String by lazy { pem("CERTIFICATE", p256bCertificate.encoded) }
    val p384Cert: String by lazy { pem("CERTIFICATE", p384Certificate.encoded) }
}
