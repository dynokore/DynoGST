package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.evidence.api.PrivacyApi
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.privacy.PrivacyCases
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404

/** ClosedErrorCode.NOT_FOUND of service-runtime: what evidence-svc answers for an unknown case or one of another tenant. */
private const val NOT_FOUND_CODE = "SRT-005"

/**
 * ARC-08, TPL-07: the operation getCase of evidence-svc (`console-bff->evidence-svc`), through the generated client
 * and the policy of the edge; the `status` member of the case is returned as text. The owner's refusal is relayed
 * unchanged (RFC 9457), except that a 404 whose problem body carries exactly the code [NOT_FOUND_CODE] is the answer
 * `Ok(null)` for an unknown case; a 404 without a readable problem body (a proxy or a route) stays Unavailable (fail
 * closed). A case id that is not a UUID is never sent.
 */
@Component
class PrivacyCaseClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : PrivacyCases {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.EVIDENCE)
    private val privacy = PrivacyApi(restClient)
    private val edge = OwnerEdge(OwningService.EVIDENCE, registry, clock, restClient)

    override fun status(caseId: String): OwnerReply<String?> {
        val id = OwningServiceEdge.strictUuid(caseId)
        return if (id == null) {
            OwnerReply.Unavailable("${OwningService.EVIDENCE.edge}: caseId is not a UUID")
        } else {
            val reply =
                edge.call({ privacy.getCaseRequestConfig(id) }) { answer ->
                    val status = OwnerEnvelope.tree(answer.body)?.let { OwnerEnvelope.text(it, "status") }
                    if (answer.status == HTTP_OK &&
                        status != null
                    ) {
                        OwnerReply.Ok(status)
                    } else {
                        edge.unreadable(answer.status)
                    }
                }
            val refusal = (reply as? OwnerReply.Refused)?.refusal
            if (refusal != null && refusal.status == HTTP_NOT_FOUND && refusal.code == NOT_FOUND_CODE) {
                OwnerReply.Ok(null)
            } else {
                reply
            }
        }
    }
}
