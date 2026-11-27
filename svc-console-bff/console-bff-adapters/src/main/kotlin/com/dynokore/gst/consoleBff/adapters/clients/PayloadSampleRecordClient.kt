package com.dynokore.gst.consoleBff.adapters.clients

import com.dynokore.gst.consoleBff.adapters.clients.payload.api.RenditionApi
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleMeta
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleRecordRead
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleReply
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.Base64

private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * ARC-08, TPL-07: the operation getRenditionSampleCheck of payload-svc (`console-bff->payload-svc`), through the
 * generated client and the policy of the edge, for the single-sample read only. `artefactBytes` is neither read nor
 * passed on (tolerant reader, API-41). The token comes from the interceptor of the bean `internalRestClientBuilder`;
 * this class sets no header. It is not scanned: the composition root imports it.
 */
class PayloadSampleRecordClient(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    clock: Clock,
    environment: Environment,
) : SampleRecordRead {
    private val restClient = OwningServiceEdge.restClient(builder, environment, OwningService.PAYLOAD)
    private val rendition = RenditionApi(restClient)
    private val sampleRead = PayloadSampleRead(registry, clock, restClient)

    override fun read(
        entityRef: String,
        checkId: String,
    ): SampleReply<SampleRecord> =
        sampleRead.read(
            entityRef,
            checkId,
            { rendition.getRenditionSampleCheckRequestConfig(entityRef, checkId) },
            ::record,
        )

    private fun record(root: JsonNode): SampleRecord? {
        val check = root.get("check")?.takeIf { it.isObject } ?: return null
        val meta = SampleCheckMembers.of(check)
        val content = OwnerEnvelope.text(check, "sampleContent")?.let { strictBase64(it) }
        return if (meta == null || content == null) {
            null
        } else {
            SampleRecord(
                checkId = meta.checkId,
                entityRef = meta.entityRef,
                scenario = meta.scenario,
                submissionType = meta.submissionType,
                sampleContent = content,
                sampleSha256 = meta.sampleSha256,
                result = meta.result,
                textFound = meta.textFound,
                missingWording = meta.missingWording,
                checkedAt = meta.checkedAt,
                checkedBy = meta.checkedBy,
                findingCode = meta.findingCode,
            )
        }
    }

    /**
     * RFC 4648 section 3.5: only the canonical padded form. The JDK decoder alone accepts a missing padding and
     * non-zero pad bits, so the text must also be the encoding of what it decodes to.
     */
    private fun strictBase64(text: String): ByteArray? =
        try {
            Base64.getDecoder().decode(text).takeIf { Base64.getEncoder().encodeToString(it) == text }
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * The members of the stored sample check that both payload reads carry, as the [SampleMeta] of the application. Only
 * `findingCode` is optional.
 */
internal object SampleCheckMembers {
    /** Null when a required member is absent or any member is badly typed; none is filled with a default. */
    fun of(check: JsonNode): SampleMeta? {
        val texts = REQUIRED_TEXTS.mapNotNull { name -> OwnerEnvelope.text(check, name)?.let { name to it } }.toMap()
        val textFound = boolean(check, "textFound")
        val missingWording = textList(check, "missingWording")
        val checkedAt = OwnerEnvelope.instant(check, "checkedAt")
        val findingCode = OwnerEnvelope.text(check, "findingCode")
        // findingCode is absent on a PASSED check; present, it must be a text
        val textsReadable = texts.size == REQUIRED_TEXTS.size && (!check.has("findingCode") || findingCode != null)
        val othersReadable = listOf(textFound, missingWording, checkedAt).none { it == null }
        return if (!textsReadable || !othersReadable) {
            null
        } else {
            SampleMeta(
                checkId = texts.getValue("checkId"),
                entityRef = texts.getValue("entityRef"),
                scenario = texts.getValue("scenario"),
                submissionType = texts.getValue("submissionType"),
                sampleSha256 = texts.getValue("sampleSha256"),
                result = texts.getValue("result"),
                textFound = checkNotNull(textFound),
                missingWording = checkNotNull(missingWording),
                checkedAt = checkNotNull(checkedAt),
                checkedBy = texts.getValue("checkedBy"),
                findingCode = findingCode,
            )
        }
    }

    fun boolean(
        node: JsonNode,
        member: String,
    ): Boolean? = node.get(member)?.takeIf { it.isBoolean }?.booleanValue()

    private fun textList(
        node: JsonNode,
        member: String,
    ): List<String>? {
        val array = node.get(member)?.takeIf { it.isArray } ?: return null
        val items = (0 until array.size()).map { array.get(it) }
        return if (items.all { it.isString }) items.map { it.stringValue() } else null
    }

    private val REQUIRED_TEXTS =
        listOf("checkId", "entityRef", "scenario", "submissionType", "sampleSha256", "result", "checkedBy")
}

/**
 * One read of one sample check of payload-svc, shared by the two payload sample clients. Only a 200 whose every member
 * used is present and well typed is a value (ASVS V4.1.1: fail closed). A refusal with 400, 403 or 404 keeps the
 * owner's envelope unchanged (RFC 9457); any other answer is Unavailable.
 */
internal class PayloadSampleRead(
    registry: ResilienceRegistry,
    clock: Clock,
    restClient: RestClient,
) {
    private val service = OwningService.PAYLOAD
    private val edge = OwnerEdge(service, registry, clock, restClient)

    fun <T> read(
        entityRef: String,
        checkId: String,
        request: () -> RequestConfig<*>,
        value: (JsonNode) -> T?,
    ): SampleReply<T> =
        if (!isPathSegment(entityRef) || !isPathSegment(checkId)) {
            // a dot segment is not encoded in a path and would address another resource
            unavailable("entityRef or checkId is not a path segment")
        } else {
            try {
                val reply =
                    edge.call(request) { answer ->
                        val tree = if (answer.status == HTTP_OK) OwnerEnvelope.tree(answer.body) else null
                        val read = tree?.let(value)
                        if (read != null) OwnerReply.Ok(read) else edge.unreadable(answer.status)
                    }
                translate(reply)
            } catch (_: RestClientException) {
                // no exception crosses the port
                unavailable("call failed")
            } catch (_: IllegalArgumentException) {
                // not a RestClientException: a header that does not parse, such as Content-Type (ASVS V7.4)
                unavailable("unreadable answer")
            }
        }

    private fun isPathSegment(text: String) = text.isNotBlank() && text != "." && text != ".."

    private fun unavailable(why: String) = SampleReply.Unavailable("${service.edge}: $why")

    private fun <T> translate(reply: OwnerReply<T>): SampleReply<T> =
        when (reply) {
            is OwnerReply.Ok -> {
                SampleReply.Ok(reply.value)
            }

            is OwnerReply.Unavailable -> {
                SampleReply.Unavailable(reply.reason)
            }

            is OwnerReply.Refused -> {
                when (reply.refusal.status) {
                    HTTP_BAD_REQUEST -> SampleReply.Invalid(reply.refusal)
                    HTTP_FORBIDDEN -> SampleReply.Forbidden(reply.refusal)
                    HTTP_NOT_FOUND -> SampleReply.NotFound(reply.refusal)
                    else -> unavailable("status ${reply.refusal.status}")
                }
            }
        }
}
