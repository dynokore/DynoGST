package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.payload.api.RenditionApi
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleMetaRead
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleMetaReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Clock

/** The `pattern` of `sampleSha256` in the owner's contract. */
private val SAMPLE_SHA256 = Regex("[0-9a-f]{64}")

/**
 * ARC-08, TPL-07: the operation getRenditionSampleCheckMetadata of payload-svc (`console-bff->payload-svc`), through
 * the generated client and the policy of the edge, for the list join. It reads no byte member and never calls
 * getRenditionSampleCheck (OWASP API4); any extra member is ignored (tolerant reader, API-41). The token comes from the
 * interceptor of the bean `internalRestClientBuilder`; this class sets no header. It is not scanned: the composition
 * root imports it.
 */
class PayloadSampleMetaClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : SampleMetaRead {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.PAYLOAD)
    private val rendition = RenditionApi(restClient)
    private val sampleRead = PayloadSampleRead(registry, clock, restClient)

    override fun read(
        entityRef: String,
        checkId: String,
    ): SampleReply<SampleMetaReply> =
        sampleRead.read(
            entityRef,
            checkId,
            { rendition.getRenditionSampleCheckMetadataRequestConfig(entityRef, checkId) },
            ::reply,
        )

    private fun reply(root: JsonNode): SampleMetaReply? {
        val check = root.get("check")?.takeIf { it.isObject }
        val meta = check?.let { SampleCheckMembers.of(it) }?.takeIf { SAMPLE_SHA256.matches(it.sampleSha256) }
        val standing = root.get("standing")?.takeIf { it.isObject }
        val open = standing?.let { SampleCheckMembers.boolean(it, "open") }
        return if (meta == null || open == null) null else SampleMetaReply(meta, standingOpen = open)
    }
}
