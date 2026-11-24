package com.dynokore.gst.access.adapters.token

import com.dynokore.gst.access.application.token.AccessTokenClaims
import com.dynokore.gst.access.application.token.AccessTokenSigner
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.application.token.SigningSession
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * The `SecretStore` adapter of [AccessTokenSigner] (STK-29, CS-24). The signing key is read from the secret
 * [SIGNING_KEY_SECRET] at every use, so a replaced secret needs no restart, and is a P-256 private JWK (RFC 7517). The
 * `kid` is the first 16 hexadecimal characters of the SHA-256 of the DER public key. The key never leaves the session:
 * it is not returned, not logged, and redacted in `toString()`. Any read or parse failure is [SigningKeyUnavailable]
 * with a fixed text, never the secret's content.
 */
class NimbusAccessTokenSigner(
    private val secrets: SecretStore,
) : AccessTokenSigner {
    override fun open(): SigningSession = NimbusSigningSession(readSigningKey(secrets))

    companion object {
        const val SIGNING_KEY_SECRET = "access/token-signing-key"
    }
}

/**
 * Reads the one signing key of access-svc from the binding [NimbusAccessTokenSigner.SIGNING_KEY_SECRET]: it signs
 * access tokens and flow contexts alike, so a service verifies both with the keys it already holds. Any read or parse
 * failure, or a key that is not a P-256 private key, is [SigningKeyUnavailable] with a fixed text.
 */
internal fun readSigningKey(secrets: SecretStore): ECKey {
    val key =
        try {
            ECKey.parse(secrets.read(SecretRef(NimbusAccessTokenSigner.SIGNING_KEY_SECRET)).reveal())
        } catch (_: Exception) {
            throw SigningKeyUnavailable("the signing key secret is unreadable")
        }
    if (!key.isPrivate || key.curve != Curve.P_256) {
        throw SigningKeyUnavailable("the signing key secret is not a P-256 private key")
    }
    return key
}

/** The `kid` of a signing key: the first 16 hexadecimal characters of the SHA-256 of the DER public key. */
internal fun keyIdOf(key: ECKey): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(key.toECPublicKey().encoded)
        .take(KID_BYTES)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

private const val KID_BYTES = 8

private class NimbusSigningSession(
    private val key: ECKey,
) : SigningSession {
    private val der: ByteArray = key.toECPublicKey().encoded

    override val kid: String = keyIdOf(key)
    override val alg: String = JWSAlgorithm.ES256.name
    override val publicKey: String = Base64.getEncoder().encodeToString(der)

    override fun sign(claims: AccessTokenClaims): String {
        // fail closed (INV-REQ-584, ASVS V4.1): a machine token without its type is never signed, no inference
        val principalType =
            requireNotNull(claims.principalType) { "a machine access token needs the claim principal_type" }
        val set =
            JWTClaimsSet
                .Builder()
                .issuer(claims.issuer)
                .subject(claims.subject)
                .audience(listOf(claims.audience))
                .issueTime(Date.from(claims.issuedAt))
                .expirationTime(Date.from(claims.expiresAt))
                .jwtID(claims.tokenId)
                .claim("client_id", claims.subject)
                .claim("scope", claims.scope)
                .claim("principal_scope", claims.principalScope)
                .claim("principal_type", principalType)
                .claim("permissions", claims.permissions.sorted())
                .apply {
                    claims.tenantId?.let { tenant ->
                        claim("tenant_id", tenant)
                        // the environment is a property of the tenant: no tenant (MONITORING), no claim
                        claims.environment?.let { claim("environment", it.name) }
                    }
                }.build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType("at+jwt"))
                .keyID(kid)
                .build()
        return SignedJWT(header, set).also { it.sign(ECDSASigner(key)) }.serialize()
    }

    override fun toString(): String = "SigningSession(kid=$kid, key=<redacted>)"
}
