package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.evidence.api.PrivacyApi
import com.dynokore.gst.consoleBff.adapters.clients.evidence.model.AppendEvidenceAnnotationRequest
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.privacy.EvidenceAnnotations
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201

/**
 * ARC-08, TPL-07: the operation appendEvidenceAnnotation of evidence-svc (`console-bff->evidence-svc`), through the
 * generated client and the policy of the edge. The request holds the chain id, the field name and the reason only:
 * never a corrected or a previous value. 201 (new) and 200 (replay of the same Idempotency-Key) are both Ok.
 */
@Component
class EvidenceAnnotationClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : EvidenceAnnotations {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.EVIDENCE)
    private val privacy = PrivacyApi(restClient)
    private val edge = OwnerEdge(OwningService.EVIDENCE, registry, clock, restClient)

    override fun append(
        caseId: String,
        idempotencyKey: String,
        ackId: String,
        correctedField: String,
        reason: String,
    ): OwnerReply<Unit> {
        val id = OwningServiceEdge.strictUuid(caseId)
        val key = OwningServiceEdge.strictUuid(idempotencyKey)
        return when {
            id == null -> {
                invalid("caseId is not a UUID")
            }

            key == null -> {
                invalid("idempotencyKey is not a UUID")
            }

            else -> {
                val body =
                    AppendEvidenceAnnotationRequest(ackId = ackId, correctedField = correctedField, reason = reason)
                edge.call({ privacy.appendEvidenceAnnotationRequestConfig(id, key, body) }) { answer ->
                    val readable =
                        OwnerEnvelope.tree(answer.body)?.let { OwnerEnvelope.text(it, "annotationId") } != null
                    if ((answer.status == HTTP_CREATED || answer.status == HTTP_OK) && readable) {
                        OwnerReply.Ok(Unit)
                    } else {
                        edge.unreadable(answer.status)
                    }
                }
            }
        }
    }

    private fun invalid(why: String) = OwnerReply.Unavailable("${OwningService.EVIDENCE.edge}: $why")
}
