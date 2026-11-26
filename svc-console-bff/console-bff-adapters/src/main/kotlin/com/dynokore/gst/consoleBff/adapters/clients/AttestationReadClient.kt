package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.tenancy.api.PeriodsApi
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRead
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.EvidencePage
import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.time.Clock

private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * ARC-08, TPL-07: the operation listRenditionAttestations of tenancy-svc (`console-bff->tenancy-svc`), through the
 * generated client and the policy of the edge. The token comes from the interceptor of the bean
 * `internalRestClientBuilder`; this class sets no header. It is not scanned: the composition root imports it.
 */
class AttestationReadClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : AttestationRead {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.TENANCY)
    private val periods = PeriodsApi(restClient)
    private val read = OwnerPageRead(OwningService.TENANCY, registry, clock, restClient)

    override fun page(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): PageReply<AttestationRecord> =
        read.page(
            entityRef,
            { periods.listRenditionAttestationsRequestConfig(entityRef, limit, cursor) },
            ::record,
        )

    private fun record(node: JsonNode): AttestationRecord? {
        val documentClass = OwnerEnvelope.text(node, "documentClass")
        val renderer = OwnerEnvelope.text(node, "renderer")
        val attestedBy = OwnerEnvelope.text(node, "attestedBy")
        val attestedAt = OwnerEnvelope.instant(node, "attestedAt")
        val sequence = OwnerEnvelope.integer(node, "sequence")?.takeIf { it >= 1 }
        return when {
            documentClass == null || renderer == null || attestedBy == null -> null
            attestedAt == null || sequence == null -> null
            else -> AttestationRecord(documentClass, renderer, attestedBy, attestedAt, sequence)
        }
    }
}

/**
 * One page read of an owner's cursor list (API-18), shared by the clients of the two rendition evidence reads. Only a
 * 200 whose every member is present and well typed is a page; none is filled with a default (ASVS V4.1.1: fail
 * closed). A refusal with 400, 403 or 404 keeps the owner's envelope unchanged; any other answer is Unavailable.
 */
internal class OwnerPageRead(
    private val service: OwningService,
    registry: ResilienceRegistry,
    clock: Clock,
    restClient: RestClient,
) {
    private val edge = OwnerEdge(service, registry, clock, restClient)

    fun <T> page(
        entityRef: String,
        request: () -> RequestConfig<*>,
        item: (JsonNode) -> T?,
    ): PageReply<T> =
        if (entityRef.isBlank() || entityRef == "." || entityRef == "..") {
            // a dot segment is not encoded in a path and would address another resource
            unavailable("entityRef is not a path segment")
        } else {
            try {
                val reply =
                    edge.call(request) { answer ->
                        val page = if (answer.status == HTTP_OK) read(answer.body, item) else null
                        if (page != null) OwnerReply.Ok(page) else edge.unreadable(answer.status)
                    }
                translate(reply)
            } catch (_: RestClientException) {
                // no exception crosses the port
                unavailable("call failed")
            } catch (_: IllegalArgumentException) {
                // not a RestClientException: a header that does not parse, such as Content-Type (InvalidMediaType) or
                // Content-Length (NumberFormat) (ASVS V7.4: fail closed)
                unavailable("unreadable answer")
            }
        }

    private fun unavailable(why: String) = PageReply.Unavailable("${service.edge}: $why")

    private fun <T> translate(reply: OwnerReply<EvidencePage<T>>): PageReply<T> =
        when (reply) {
            is OwnerReply.Ok -> {
                PageReply.Ok(reply.value)
            }

            is OwnerReply.Unavailable -> {
                PageReply.Unavailable(reply.reason)
            }

            is OwnerReply.Refused -> {
                when (reply.refusal.status) {
                    HTTP_BAD_REQUEST -> PageReply.Invalid(reply.refusal)
                    HTTP_FORBIDDEN -> PageReply.Forbidden(reply.refusal)
                    HTTP_NOT_FOUND -> PageReply.NotFound(reply.refusal)
                    else -> unavailable("status ${reply.refusal.status}")
                }
            }
        }

    private fun <T> read(
        raw: String?,
        item: (JsonNode) -> T?,
    ): EvidencePage<T>? = OwnerEnvelope.tree(raw)?.let { members(it, item) }

    private fun <T> members(
        root: JsonNode,
        item: (JsonNode) -> T?,
    ): EvidencePage<T>? {
        val array = root.get("items")?.takeIf { it.isArray } ?: return null
        val items = (0 until array.size()).map { item(array.get(it)) }
        // absent on the last page; present, it must be a non-blank text
        val cursor = OwnerEnvelope.text(root, "nextCursor")?.takeIf { it.isNotBlank() }
        return if (items.any { it == null } || (root.has("nextCursor") && cursor == null)) {
            null
        } else {
            EvidencePage(items.map { checkNotNull(it) }, cursor)
        }
    }
}
