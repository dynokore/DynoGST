package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.masterdata.api.PrivacyApi
import com.dynokore.gst.consoleBff.adapters.clients.masterdata.model.CorrectCounterpartyDataRequest
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.privacy.CorrectedVersion
import com.dynokore.gst.consoleBff.application.privacy.CorrectionRequest
import com.dynokore.gst.consoleBff.application.privacy.CounterpartyCorrections
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock

private const val HTTP_OK = 200

/**
 * ARC-08, TPL-07: the operation correctCounterpartyData of masterdata-svc (`console-bff->masterdata-svc`), through
 * the generated client and the policy of the edge. The owner's refusal is relayed unchanged (RFC 9457). The case id,
 * the idempotency key and the field are sent only when valid; anything else is Unavailable without a call.
 */
@Component
class CounterpartyCorrectionClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : CounterpartyCorrections {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.MASTERDATA)
    private val privacy = PrivacyApi(restClient)
    private val edge = OwnerEdge(OwningService.MASTERDATA, registry, clock, restClient)

    override fun correct(
        counterpartyRef: String,
        idempotencyKey: String,
        request: CorrectionRequest,
    ): OwnerReply<CorrectedVersion> {
        val key = OwningServiceEdge.strictUuid(idempotencyKey)
        val caseId = OwningServiceEdge.strictUuid(request.caseId)
        val field = CorrectCounterpartyDataRequest.Field.entries.firstOrNull { it.value == request.field }
        return when {
            counterpartyRef.isBlank() -> {
                invalid("counterpartyRef is blank")
            }

            key == null -> {
                invalid("idempotencyKey is not a UUID")
            }

            caseId == null -> {
                invalid("caseId is not a UUID")
            }

            field == null -> {
                invalid("field is not a correctable field")
            }

            else -> {
                val body =
                    CorrectCounterpartyDataRequest(
                        entityRef = request.entityRef,
                        caseId = caseId,
                        field = field,
                        correctedValue = request.correctedValue,
                        reason = request.reason,
                    )
                edge.call({ privacy.correctCounterpartyDataRequestConfig(counterpartyRef, key, body) }) { answer ->
                    val tree = OwnerEnvelope.tree(answer.body)
                    val recordedAt = tree?.let { OwnerEnvelope.instant(it, "recordedAt") }
                    if (answer.status == HTTP_OK && recordedAt != null) {
                        OwnerReply.Ok(CorrectedVersion(recordedAt))
                    } else {
                        edge.unreadable(answer.status)
                    }
                }
            }
        }
    }

    private fun invalid(why: String) = OwnerReply.Unavailable("${OwningService.MASTERDATA.edge}: $why")
}
