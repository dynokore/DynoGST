package com.dynokore.gst.access.adapters.token

import com.dynokore.gst.access.application.flowcontext.FlowContextClaims
import com.dynokore.gst.access.application.flowcontext.FlowContextSigner
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.util.Base64

/**
 * The `SecretStore` adapter of [FlowContextSigner] (INV-REQ-1202, owner answer 2026-11-18, option A). It signs with the
 * key access-svc already holds for its tokens: the same binding ([readSigningKey]) and the same
 * [VerificationKeyRegistry], so no second key exists and a service verifies a flow context with the keys it already
 * holds. The compact form (JWS, RFC 7515) carries the JOSE header `typ` [FLOW_CONTEXT_TYPE], never `at+jwt`, so a flow
 * context is refused as an access token and the other way round (RFC 8725 section 3.11). The key is read at every use;
 * its public part is published before it signs, and a key past its signing interval is refused (fail closed), as for
 * tokens. The compact form and the key are never logged.
 */
class NimbusFlowContextSigner(
    private val secrets: SecretStore,
    private val registry: VerificationKeyRegistry,
    private val settings: TokenSettings,
    private val clock: Clock,
) : FlowContextSigner {
    override fun sign(claims: FlowContextClaims): String {
        val key = readSigningKey(secrets)
        val kid = keyIdOf(key)
        publish(key, kid)
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType(FLOW_CONTEXT_TYPE))
                .keyID(kid)
                .build()
        return SignedJWT(header, claimSetOf(claims)).also { it.sign(ECDSASigner(key)) }.serialize()
    }

    /** Publishes the public key as the token path does and refuses a key past its signing interval (fail closed). */
    private fun publish(
        key: ECKey,
        kid: String,
    ) {
        val now = clock.instant()
        val publicKey = Base64.getEncoder().encodeToString(key.toECPublicKey().encoded)
        val alg = JWSAlgorithm.ES256.name
        val published =
            registry.register(
                VerificationKey(
                    kid = kid,
                    alg = alg,
                    publicKey = publicKey,
                    validFrom = now,
                    validUntil = now.plus(settings.keyRotationInterval).plus(settings.lifetime),
                ),
            )
        if (published.publicKey != publicKey || published.alg != alg) {
            throw SigningKeyUnavailable("the published key of $kid differs from the signing key")
        }
        if (!now.isBefore(published.validFrom.plus(settings.keyRotationInterval))) {
            throw SigningKeyUnavailable("the signing key $kid is past its signing interval")
        }
    }

    private fun claimSetOf(claims: FlowContextClaims): JWTClaimsSet {
        val entity =
            buildMap<String, Any> {
                put("kind", claims.entity.kind.name)
                claims.entity.ref?.let { put("ref", it) }
            }
        return JWTClaimsSet
            .Builder()
            .claim("tenant", claims.tenant.toString())
            .claim("entity", entity)
            .claim("principalKind", claims.principalKind.name)
            .claim("callerService", claims.callerService)
            .claim("flowId", claims.flowId)
            .claim("iat", claims.iat)
            .claim("exp", claims.exp)
            .build()
    }

    companion object {
        /** The JOSE `typ` of a flow context (RFC 8725 section 3.11); the access-token `typ` is `at+jwt`. */
        const val FLOW_CONTEXT_TYPE = "flow-context+jwt"
    }
}
