package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.rules.api.EvaluationApi
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluateDocumentRequest
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluateDocumentRequestCounterpartySchemeData
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluationResult
import com.dynokore.gst.canonical.adapters.clients.rules.model.EvaluationResultSelfBillingAssignment
import com.dynokore.gst.canonical.application.document.AppliedPackFact
import com.dynokore.gst.canonical.application.document.SelfBillingPairFact
import com.dynokore.platform.serviceruntime.identity.FlowContext
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

private const val SCOPE = "SCOPE"
private const val MAPPING = "MAPPING"
private const val MDE = "MDE"
private val PACK_TYPES = listOf(SCOPE, MAPPING, MDE)
private const val HTTP_FORBIDDEN = 403
private const val FLOW_CONTEXT_REFUSED = "SRT-004"

private const val UNUSED_FLOW_CONTEXT = ""
private val ENVELOPE = JsonMapper.builder().build()

/** The wire values of the members of `selfBillingAssignment`, from the generated enums. */
private val ASSIGNMENT_VALUES =
    mapOf(
        "direction" to EvaluationResultSelfBillingAssignment.Direction.entries.map { it.value },
        "submissionType" to EvaluationResultSelfBillingAssignment.SubmissionType.entries.map { it.value },
    )

/** The sync edge canonical-svc -> rules-svc (sync-edges.yaml; `x-sync-edge` of `evaluateDocument`). */
object RulesEdge {
    const val EDGE = "canonical-svc->rules-svc"
    const val URL_PROPERTY = "canonical.rules-svc-url"

    /** `x-timeout` of `evaluateDocument`. */
    val DEADLINE: Duration = Duration.ofMillis(300)

    /** Required, not blank, not an unresolved placeholder, no default (TPL-16, twelve-factor config). */
    fun baseUrl(environment: Environment): String {
        val value =
            try {
                environment.getProperty(URL_PROPERTY)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("$URL_PROPERTY has an unresolvable placeholder", failure)
            }
        requireNotNull(value) { "$URL_PROPERTY is required and has no default" }
        require(value.isNotBlank()) { "$URL_PROPERTY must not be blank" }
        require(!value.trim().startsWith("\${")) { "$URL_PROPERTY is an unresolved placeholder: $value" }
        return value
    }
}

/** The counterparty's scheme facts from the masterdata-svc resolution (FR-1.14, FR-1.15). */
data class SchemeData(
    val approvedSchemes: List<String>,
    val arcsGstChargeAgreed: Boolean? = null,
)

/** The inputs of one evaluation; the deprecated `election` is never part of them. */
data class EvaluationFacts(
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val electedSchemes: List<String>,
    val counterpartySchemeData: SchemeData?,
)

/** What rules-svc (or the policy of the edge) answered; no exception reaches the caller (ARC-19). */
sealed interface RulesAnswer {
    data class Verdict(
        val scopeVerdict: String,
        val documentTypeCode: String?,
        val categoryCode: String? = null,
        val rate: String? = null,
        val scopePackVersion: String? = null,
        val mappingPackVersion: String? = null,
        val mdePackVersion: String? = null,
        val appliedPacks: List<AppliedPackFact> = emptyList(),
        val supplyType: String? = null,
        val selfBillingAssignment: SelfBillingPairFact? = null,
    ) : RulesAnswer

    /** No answer: a 5xx, a timeout, an I/O error, an open breaker or a full bulkhead. The caller holds. */
    data object Unavailable : RulesAnswer

    /** A 4xx of rules-svc: a rules decision, not an outage. */
    data class Refused(
        val status: Int,
    ) : RulesAnswer
}

/**
 * TPL-07, ARC-08: the HTTP client of rules-svc for canonical-svc (`evaluateDocument`), over the client generated
 * from the owner's contract (STK-46) and the policy of the sync edge. Not a Spring component: the caller wires it.
 */
class RulesClient(
    builder: RestClient.Builder,
    private val registry: ResilienceRegistry,
    environment: Environment,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val api =
        EvaluationApi(
            builder
                .clone()
                .baseUrl(RulesEdge.baseUrl(environment))
                // the answer is read twice: by the exactness check, then by the generated client
                .bufferContent { _, _ -> true }
                .requestInterceptor { request, body, execution ->
                    val response = execution.execute(request, body)
                    if (response.statusCode.is2xxSuccessful && !hasExactAssignment(response.body.readAllBytes())) {
                        throw RestClientException("selfBillingAssignment holds a value outside the contract")
                    }
                    response
                }.build(),
        )

    /**
     * The decoder of the generated enums also takes padded text and ordinals, which would guess a value. So each
     * member of a present `selfBillingAssignment` must be a string equal to a wire value (INV-AC-29288). A body that
     * is not JSON is left to the generated client.
     */
    private fun hasExactAssignment(body: ByteArray): Boolean {
        val assignment =
            try {
                ENVELOPE.readTree(body)?.get("selfBillingAssignment")
            } catch (_: JacksonException) {
                null
            }
        return assignment == null ||
            assignment.isNull ||
            ASSIGNMENT_VALUES.all { (member, values) ->
                val text = assignment.get(member)
                text != null && text.isString && text.asString() in values
            }
    }

    fun evaluate(facts: EvaluationFacts): RulesAnswer {
        // API-29: the deadline of the request being served; a call with none behind it gets the contract's 300 ms
        val deadline = CurrentDeadline.current() ?: Deadline.after(RulesEdge.DEADLINE, clock)
        // the edge runs the block on its own thread and the held flow context is thread-bound:
        // carry it over, never make one
        val held = FlowContext.current()
        return registry.forEdge(RulesEdge.EDGE).execute(
            deadline,
            onCallNotPermitted = { RulesAnswer.Unavailable },
            onConnectFailure = { RulesAnswer.Unavailable },
            onTimeout = { RulesAnswer.Unavailable },
        ) {
            try {
                val call = { api.evaluateDocumentWithHttpInfo(UNUSED_FLOW_CONTEXT, request(facts)).body }
                val body = if (held == null) call() else FlowContext.runWith(held, call)
                if (body == null) {
                    RulesAnswer.Unavailable
                } else {
                    verdictOf(body)
                }
            } catch (failure: RestClientResponseException) {
                when {
                    isFlowContextRefusal(failure) -> RulesAnswer.Unavailable
                    failure.statusCode.is4xxClientError -> RulesAnswer.Refused(failure.statusCode.value())
                    else -> RulesAnswer.Unavailable
                }
            } catch (_: RestClientException) {
                // an I/O error or an unreadable answer: no usable answer from the owner
                RulesAnswer.Unavailable
            }
        }
    }

    /**
     * 403 whose API-20 error envelope (`application/problem+json`, field `code`) is exactly `SRT-004`: the callee
     * refused the flow context (missing, expired or for another entity). That is a wiring fault, not a rules
     * decision: the record is held and retried. Exact, case-sensitive equality; any other 403 stays a refusal.
     */
    private fun isFlowContextRefusal(failure: RestClientResponseException): Boolean =
        failure.statusCode.value() == HTTP_FORBIDDEN &&
            errorCodeOf(failure.responseBodyAsString) == FLOW_CONTEXT_REFUSED

    /** The top-level `code` string of the envelope; null for a body that is not an object with a string `code`. */
    private fun errorCodeOf(body: String): String? =
        try {
            val code = ENVELOPE.readTree(body)?.takeIf { it.isObject }?.get("code")
            if (code != null && code.isString) code.asString() else null
        } catch (_: JacksonException) {
            null
        }

    /**
     * The pack versions come from `packVersionsApplied` by exact, case-sensitive packType equality (no prefix, no case
     * folding); other types are ignored and an absent type is null. Two items of one wanted type with different
     * versions are ambiguous, and an item of a wanted type without a (non-blank) packIdentifier is unusable: both fail
     * closed to Unavailable (INV-Q-21510, INV-AC-22910). appliedPacks lists the wanted items in the order SCOPE,
     * MAPPING, MDE, copied unchanged.
     */
    private fun verdictOf(body: EvaluationResult): RulesAnswer {
        val applied = body.packVersionsApplied.orEmpty()
        val versions =
            PACK_TYPES.associateWith { type ->
                applied.filter { it.packType == type }.map { it.version }.toSet()
            }
        val wanted = applied.filter { it.packType in PACK_TYPES }
        // fail closed: no identifier is derived from the pack type or the version (INV-AC-22910)
        val missingIdentifier = wanted.any { it.packIdentifier.isNullOrBlank() }
        return if (versions.values.any { it.size > 1 } || missingIdentifier) {
            RulesAnswer.Unavailable
        } else {
            RulesAnswer.Verdict(
                scopeVerdict = body.scopeVerdict.value,
                documentTypeCode = body.documentTypeCode,
                categoryCode = body.categoryCode,
                rate = body.rate?.toPlainString(),
                supplyType = body.supplyType,
                selfBillingAssignment =
                    body.selfBillingAssignment?.let {
                        SelfBillingPairFact(it.direction.value, it.submissionType.value)
                    },
                scopePackVersion = versions.getValue(SCOPE).singleOrNull(),
                mappingPackVersion = versions.getValue(MAPPING).singleOrNull(),
                mdePackVersion = versions.getValue(MDE).singleOrNull(),
                appliedPacks =
                    PACK_TYPES.flatMap { type ->
                        wanted.filter { it.packType == type }.map {
                            AppliedPackFact(it.packType, requireNotNull(it.packIdentifier), it.version)
                        }
                    },
            )
        }
    }

    // `election` is deprecated and read only when electedSchemes is absent: it is never sent.
    private fun request(facts: EvaluationFacts) =
        EvaluateDocumentRequest(
            entityRef = facts.entityRef,
            transactionDate = facts.transactionDate,
            internalTaxCode = facts.internalTaxCode,
            electedSchemes = facts.electedSchemes.toSet(),
            counterpartySchemeData =
                facts.counterpartySchemeData?.let {
                    EvaluateDocumentRequestCounterpartySchemeData(
                        approvedSchemes = it.approvedSchemes,
                        arcsGstChargeAgreed = it.arcsGstChargeAgreed,
                    )
                },
        )
}
