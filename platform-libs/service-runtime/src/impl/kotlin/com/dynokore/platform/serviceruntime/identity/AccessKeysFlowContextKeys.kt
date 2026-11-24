package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException

/**
 * The [FlowContextKeys] of a service: verifies a flow context as a JWS (RFC 7515) against the keys the service already
 * holds for access-svc tokens, the machine-access set of the [authority] replica (`AuthorityReplica.machineAccessKeys`,
 * fed by `OperatorSessionKeysChanged`); no key is fetched and there is no second trust list. The compact form is accepted
 * only when the JOSE header `typ` equals [FlowContext.TYP] by string equality (RFC 8725 section 3.11), its `kid` names a
 * held key valid now, its `alg` is that key's own algorithm and an RSA or EC one (the allow-list of the access-token
 * verifier: `none` and HMAC are refused), and the signature verifies. Anything else, with or without an exception inside
 * the parser, gives null. No replica: always null.
 */
class AccessKeysFlowContextKeys(
    private val authority: AuthorityReplica?,
) : FlowContextKeys {
    override fun verify(compact: String): ByteArray? =
        try {
            val jwt = SignedJWT.parse(compact)
            val key =
                jwt.header.keyID
                    ?.let { authority?.machineAccessKeys?.valid(it) }
                    ?.takeIf { jwt.header.algorithm?.name == it.alg }
            val verifier = key?.let { signatureVerifierFor(jwt.header, it) }
            if (verifier != null && jwt.header.type?.type == FlowContext.TYP && jwt.verify(verifier)) {
                jwt.payload.toBytes()
            } else {
                null
            }
        } catch (_: ParseException) {
            null
        } catch (_: JOSEException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}
