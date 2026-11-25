package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.evidence.api.VerificationApi
import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckArtefactRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckRead
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Clock

/** The `pattern` of `hash` in the owner's contract. */
private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * ARC-08, TPL-07: the operation listRenditionSampleChecks of evidence-svc (`console-bff->evidence-svc`), through the
 * generated client and the policy of the edge. The token comes from the interceptor of the bean
 * `internalRestClientBuilder`; this class sets no header. It is not scanned: the composition root imports it.
 */
class SampleCheckReadClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : SampleCheckRead {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.EVIDENCE)
    private val verification = VerificationApi(restClient)
    private val read = OwnerPageRead(OwningService.EVIDENCE, registry, clock, restClient)

    override fun page(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): PageReply<SampleCheckArtefactRecord> =
        read.page(
            entityRef,
            { verification.listRenditionSampleChecksRequestConfig(entityRef, limit, cursor).withOpaqueCursor(cursor) },
            ::record,
        )

    private fun record(node: JsonNode): SampleCheckArtefactRecord? {
        val artefactType = OwnerEnvelope.text(node, "artefactType")
        val reference = OwnerEnvelope.text(node, "reference")
        val hash = OwnerEnvelope.text(node, "hash")?.takeIf { SHA256_HEX.matches(it) }
        val recordedAt = OwnerEnvelope.instant(node, "recordedAt")
        return when {
            artefactType == null || reference == null -> null
            hash == null || recordedAt == null -> null
            else -> SampleCheckArtefactRecord(artefactType, reference, hash, recordedAt)
        }
    }
}
