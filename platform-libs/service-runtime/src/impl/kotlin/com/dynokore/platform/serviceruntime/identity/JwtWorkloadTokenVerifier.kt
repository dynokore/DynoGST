package com.dynokore.platform.serviceruntime.identity

import com.nimbusds.jwt.JWTParser
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.text.ParseException

/**
 * STK-26: verifies the signature, expiry and (where configured) audience of a
 * projected service-account token through [jwtDecoder] — the same Nimbus-based
 * machinery the future end-user resource-server configuration uses (STK-27),
 * pointed instead at the cluster's own service-account issuer
 * ([WorkloadIdentityProperties.jwkSetUri]), never the platform/customer
 * `IdentityProvider` (a different, narrower trust root entirely).
 *
 * Only [JwtException] — a bad signature, an expired token, a failed issuer or
 * audience check — is caught here and turned into a refusal (INV-AC-2200,
 * CS-14, CS-15); anything else is this decoder's own infrastructure failure
 * (for example the JWK set endpoint being unreachable) and propagates.
 */
class JwtWorkloadTokenVerifier(
    private val jwtDecoder: JwtDecoder,
) : WorkloadTokenVerifier {
    override fun verify(token: WorkloadToken?): WorkloadVerification {
        if (token == null) return WorkloadVerification.Refused("missing")
        if (isFlowContext(token.value)) return WorkloadVerification.Refused("invalid")
        return try {
            val jwt = jwtDecoder.decode(token.value)
            WorkloadVerification.Verified(jwt.subject ?: "unknown")
        } catch (expected: JwtException) {
            WorkloadVerification.Refused("invalid")
        }
    }

    /** A flow context (JOSE header `typ` [FlowContext.TYP], RFC 8725 section 3.11) is never a workload token. */
    private fun isFlowContext(token: String): Boolean =
        try {
            JWTParser
                .parse(token)
                .header.type
                ?.type == FlowContext.TYP
        } catch (_: ParseException) {
            false
        }
}
