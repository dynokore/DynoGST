package com.dynokore.gst.ingestion.adapters.clients

import com.dynokore.gst.ingestion.adapters.clients.api.DocumentsApi
import com.dynokore.gst.ingestion.adapters.clients.model.ErrorEnvelope
import com.dynokore.gst.ingestion.adapters.clients.model.RecordDocumentRequest
import com.dynokore.gst.ingestion.adapters.clients.model.RecordDocumentResponse
import com.dynokore.gst.ingestion.application.recording.CanonicalRecorder
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.gst.ingestion.domain.recording.ReasonItem
import com.dynokore.gst.ingestion.domain.recording.RecordedResult
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.RequestConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * TPL-07, ARC-08: the `recordDocument` operation of canonical-svc (INV-REQ-806, INV-REQ-1192) over HTTP, through the
 * client generated from the owner's contract (STK-46) and the policy of the sync edge. A replay is canonical-svc's
 * decision and is relayed unchanged; this adapter keeps no record of it.
 */
@Component
class CanonicalRecorderAdapter(
    @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
    registry: ResilienceRegistry,
    private val clock: Clock,
    environment: Environment,
) : CanonicalRecorder {
    private val restClient = builder.baseUrl(baseUrl(environment)).build()
    private val api = DocumentsApi(restClient)
    private val transport = Transport(restClient)
    private val policy = registry.forEdge(EDGE)
    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    override fun record(command: RecordDocumentCommand): CanonicalAnswer {
        // INV-Q-11474 A: the members of command.body go out as given, none added, dropped or changed
        val tree =
            try {
                mapper.readTree(command.body)
            } catch (failure: JacksonException) {
                throw IllegalArgumentException(
                    "the body of ${command.sourceKey}/${command.sourceRevision} is not JSON",
                    failure,
                )
            }
        require(tree.isObject) { "the body of ${command.sourceKey}/${command.sourceRevision} is not a JSON object" }
        // API-29: the deadline of the request being served; a call with none behind it gets the edge's contract timeout
        val deadline = CurrentDeadline.current() ?: Deadline.after(EDGE_TIMEOUT, clock)
        return policy.execute(
            deadline,
            onCallNotPermitted = { unavailable("call not permitted (breaker open or bulkhead full)") },
            onConnectFailure = { unavailable("connection refused") },
            onTimeout = { unavailable("timed out") },
        ) {
            send(tree)
        }
    }

    /** The edge gave no answer (ARC-19, INV-REQ-1193): timeout, open breaker, full bulkhead or no connection. */
    private fun unavailable(reason: String): CanonicalAnswer = CanonicalAnswer.Unavailable("$EDGE: $reason")

    private fun send(tree: JsonNode): CanonicalAnswer =
        try {
            val response = transport.post(requestConfig(tree))
            answer(response.statusCode.value(), response.body)
        } catch (failure: RestClientResponseException) {
            rejected(failure)
        }

    /**
     * `scopeVerdict` is an extensible response enumeration (API-09, `x-extensible-enum`) carried as the
     * received string: it is taken from the JSON, and the generated model, whose enum is closed, is bound
     * with a known value in its place. A body where it is absent or not a string cannot be read.
     */
    private fun parseResponse(raw: String): SuccessBody? =
        runCatching {
            val tree = mapper.readTree(raw) as ObjectNode
            val verdict = requireNotNull(tree.get(SCOPE_VERDICT)?.takeIf { it.isString }).stringValue()
            tree.put(SCOPE_VERDICT, TEMPLATE_VERDICT.value)
            SuccessBody(mapper.treeToValue(tree, RecordDocumentResponse::class.java), verdict)
        }.getOrNull()

    /** Method, path and headers come from the generated config; only the body is replaced by the parsed tree. */
    private fun requestConfig(tree: JsonNode): RequestConfig<JsonNode> {
        val generated = api.recordDocumentRequestConfig(TEMPLATE)
        return RequestConfig(
            method = generated.method,
            path = generated.path,
            headers = generated.headers,
            params = generated.params,
            query = generated.query,
            requiresAuthentication = generated.requiresAuthentication,
            body = tree,
        )
    }

    /** The status is read before the body (INV-Q-11476 A): an unreadable body gives the status alone. */
    private fun answer(
        status: Int,
        raw: String?,
    ): CanonicalAnswer {
        val success = raw?.takeIf { it.isNotBlank() }?.let { parseResponse(it) }
        val body = success?.model
        return when {
            body == null -> {
                CanonicalAnswer.Rejected(null, status)
            }

            status == HttpStatus.CREATED.value() -> {
                CanonicalAnswer.Created(body.docUuid, body.version.toLong(), result(success))
            }

            status == HttpStatus.OK.value() -> {
                CanonicalAnswer.Replay(body.docUuid, body.version.toLong(), result(success))
            }

            status == HttpStatus.ACCEPTED.value() -> {
                CanonicalAnswer.Held(
                    body.docUuid,
                    body.version.toLong(),
                    body.reasons.orEmpty().map { it.code },
                    result(success),
                )
            }

            else -> {
                CanonicalAnswer.Rejected(null, status)
            }
        }
    }

    /** Every member is copied as received; a missing required member never reaches here (the parse failed). */
    private fun result(success: SuccessBody): RecordedResult {
        val body = success.model
        return RecordedResult(
            version = body.version.toLong(),
            scopeVerdict = success.scopeVerdict,
            transactionDate = body.transactionDate,
            prescribedPeriodRef = body.prescribedPeriodRef,
            reasonItems = body.reasons.orEmpty().map { ReasonItem(it.code, it.message, it.field) },
        )
    }

    /** [model]'s own `scopeVerdict` is a placeholder; the received one is [scopeVerdict]. */
    private class SuccessBody(
        val model: RecordDocumentResponse,
        val scopeVerdict: String,
    )

    /** A 4xx with the Error envelope carries its code and status; a 5xx or any other body carries only the status. */
    private fun rejected(failure: RestClientResponseException): CanonicalAnswer {
        val status = failure.statusCode.value()
        if (failure.statusCode.is4xxClientError) {
            val envelope = runCatching { mapper.readValue<ErrorEnvelope>(failure.responseBodyAsByteArray) }.getOrNull()
            if (envelope != null) return CanonicalAnswer.Rejected(envelope.code, envelope.status)
        }
        return CanonicalAnswer.Rejected(null, status)
    }

    /** The generated `ApiClient` sends the request; the answer comes back raw, so the status precedes any parsing. */
    private class Transport(
        client: RestClient,
    ) : ApiClient(client) {
        fun post(config: RequestConfig<JsonNode>): ResponseEntity<String> =
            prepare(defaults(config)).retrieve().toEntity(String::class.java)
    }

    private companion object {
        /** Only its method, path and headers are used: the generated config needs a typed request to be asked for. */
        val TEMPLATE =
            RecordDocumentRequest(
                tenantId = UUID(0, 0),
                entityRef = "",
                sourceSystem = "",
                sourceKey = "",
                sourceRevision = 0,
                documentFunction = RecordDocumentRequest.DocumentFunction.entries.first(),
                transactionDate = LocalDate.EPOCH,
                currency = "",
                lines = emptyList(),
            )

        const val SCOPE_VERDICT = "scopeVerdict"
        val TEMPLATE_VERDICT = RecordDocumentResponse.ScopeVerdict.entries.first()

        const val URL_PROPERTY = "ingestion.canonical-svc-url"
        const val EDGE = "ingestion-svc->canonical-svc"

        /** `x-timeout` of `recordDocument` in the canonical contract. */
        val EDGE_TIMEOUT: Duration = Duration.ofMillis(2000)

        /** Required and not blank, with no default (TPL-16, twelve-factor config): the context fails without it. */
        fun baseUrl(environment: Environment): String =
            environment.getRequiredProperty(URL_PROPERTY).also {
                require(it.isNotBlank()) { "$URL_PROPERTY must not be blank" }
            }
    }
}
