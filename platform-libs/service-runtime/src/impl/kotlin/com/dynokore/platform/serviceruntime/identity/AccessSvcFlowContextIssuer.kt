package com.dynokore.platform.serviceruntime.identity

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The [FlowContextIssuer] of a flow entry: `POST /internal/v1/access/flow-contexts` of access-svc (operation
 * `issueFlowContext`), through [client], the service's own outbound client of access-svc whose builder is
 * `InternalRestClientAutoConfiguration`'s, so the call carries the workload token of the calling service and the deadline.
 * Only the claims of the port are sent (`tenant`, `entity`, `principalKind`, `flowId`); the caller, `iat` and `exp` are
 * access-svc's. The answer must be a 2xx with a string `flowContext` and an integral `expiresAt`; a non-2xx answer, a
 * timeout, an unreadable body or a body without `flowContext` throws [FlowContextUnavailableException] (fail closed).
 */
class AccessSvcFlowContextIssuer(
    private val client: RestClient,
) : FlowContextIssuer {
    override fun issue(claims: FlowContextClaims): IssuedFlowContext {
        val entity = linkedMapOf<String, Any>("kind" to claims.entity.kind.name)
        claims.entity.ref?.let { entity["ref"] = it }
        val body =
            linkedMapOf(
                "tenant" to claims.tenant,
                "entity" to entity,
                "principalKind" to claims.principalKind,
                "flowId" to claims.flowId,
            )
        return try {
            val answer =
                client
                    .post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String::class.java)
            // only a 2xx is a success: a 3xx that is not followed is no context either
            if (!answer.statusCode.is2xxSuccessful) throw FlowContextUnavailableException()
            read(MAPPER.readTree(answer.body.orEmpty()))
        } catch (refused: FlowContextUnavailableException) {
            throw refused
        } catch (failure: RuntimeException) {
            throw FlowContextUnavailableException(failure)
        }
    }

    private fun read(root: JsonNode): IssuedFlowContext {
        val compact =
            root
                .get("flowContext")
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf { it.isNotEmpty() }
        val expiresAt = root.get("expiresAt")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
        if (compact == null || expiresAt == null) throw FlowContextUnavailableException()
        return IssuedFlowContext(compact, expiresAt)
    }

    companion object {
        /** The path of `issueFlowContext`. */
        const val PATH = "/internal/v1/access/flow-contexts"
        private val MAPPER = JsonMapper.builder().build()
    }
}
