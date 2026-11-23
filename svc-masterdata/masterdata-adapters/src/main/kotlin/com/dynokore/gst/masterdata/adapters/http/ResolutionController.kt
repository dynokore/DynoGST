package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.CorrectCounterpartyData
import com.dynokore.gst.masterdata.application.counterparty.CorrectableField
import com.dynokore.gst.masterdata.application.counterparty.CorrectionCommand
import com.dynokore.gst.masterdata.application.counterparty.CorrectionResult
import com.dynokore.gst.masterdata.application.counterparty.PartyAnswer
import com.dynokore.gst.masterdata.application.counterparty.PartyKey
import com.dynokore.gst.masterdata.application.counterparty.PartyQuestion
import com.dynokore.gst.masterdata.application.counterparty.ReadCounterpartyHistory
import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.counterparty.ResolveCounterparty
import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.registry.ResolveReachability
import com.dynokore.gst.masterdata.contract.api.PrivacyApi
import com.dynokore.gst.masterdata.contract.api.ResolutionApi
import com.dynokore.gst.masterdata.contract.model.CorrectCounterpartyDataRequest
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.CounterpartyHistory
import com.dynokore.gst.masterdata.contract.model.CounterpartyVersion
import com.dynokore.gst.masterdata.contract.model.IdentityTripleFacts
import com.dynokore.gst.masterdata.contract.model.IngestedCounterpartyRequest
import com.dynokore.gst.masterdata.contract.model.Reachability
import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * TPL-08. `resolveCounterparty`: the identity triple in force for (entity, constitution) on the transaction date, or
 * 422 MDM-005 when none is configured (ruling INV-Q-7886 A); and, where a party is named by one of its identifiers, the
 * counterparty with its findings (a finding never changes the 200: INV-Q-8421 A), `sameTenant`, `sameGroup`, the ARCS
 * default and the approved scheme status in force on the transaction date. `getCounterpartyHistory`: the versions of
 * one counterparty in write order (INV-Q-8425 A).
 */
@RestController
@Suppress("LongParameterList")
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class ResolutionController(
    private val resolve: ResolveIdentityTriple,
    private val resolveParty: ResolveCounterparty,
    private val history: ReadCounterpartyHistory,
    private val record: RecordCounterparty,
    private val reachability: ResolveReachability,
    private val correct: CorrectCounterpartyData,
    private val correctionAudit: CounterpartyCorrectionAudit,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val idempotency: ObjectProvider<IdempotencyStore>,
    private val mapper: ObjectMapper,
) : ResolutionApi,
    PrivacyApi {
    override fun resolveCounterparty(
        entityRef: String,
        constitution: String,
        transactionDate: LocalDate,
        partyRef: String?,
        partyScheme: String?,
        partyValue: String?,
    ): ResponseEntity<ResolutionResult> {
        val entity = runCatching { EntityRef(entityRef) }.getOrElse { invalid("entityRef is invalid") }
        val legal = runCatching { Constitution(constitution) }.getOrElse { invalid("constitution is invalid") }
        // exactly one of partyRef or the pair names the party; none is the identity triple only (INV-Q-8484 A)
        if ((partyScheme == null) != (partyValue == null)) invalid("partyScheme and partyValue go together")
        if (partyRef != null && partyScheme != null) invalid("partyRef and partyScheme/partyValue exclude each other")
        return when (val resolution = resolve.handle(ResolveCommand(entity, legal, transactionDate))) {
            is Resolution.Resolved -> {
                val triple = resolution.version.triple
                val facts = IdentityTripleFacts(triple.senderId, triple.supplierId, triple.supplierGstn)
                ResponseEntity.ok(
                    withParty(facts, entityRef, NamedParty(partyRef, partyScheme, partyValue), transactionDate),
                )
            }

            is Resolution.Blocked -> {
                throw MasterdataRefusal(
                    MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED,
                    "No identity triple is configured for constitution ${legal.code} of entity ${entity.value}",
                )
            }
        }
    }

    private fun withParty(
        facts: IdentityTripleFacts,
        entityRef: String,
        named: NamedParty,
        transactionDate: LocalDate,
    ): ResolutionResult {
        val answer =
            answerFor(entityRef, named, transactionDate) ?: return ResolutionResult(facts)
        val findings = answer.findings.map(CounterpartyMapping::finding).ifEmpty { null }
        return when (answer) {
            is PartyAnswer.Unresolved -> {
                ResolutionResult(facts, findings = findings, reachability = reachabilityOf(entityRef, named, null))
            }

            is PartyAnswer.Resolved -> {
                val counterparty =
                    CounterpartyMapping.counterparty(
                        answer.version,
                        answer.schemesInForce,
                        answer.sameness,
                    )
                ResolutionResult(
                    identityTriple = facts,
                    counterparty = counterparty,
                    sameGroup = answer.sameness.sameGroup,
                    sameTenant = answer.sameness.sameTenant,
                    arcsGstChargeAgreed = counterparty.arcsGstChargeAgreed,
                    approvedSchemes = counterparty.approvedSchemes,
                    findings = findings,
                    reachability = reachabilityOf(entityRef, named, answer),
                )
            }
        }
    }

    /**
     * The reachability fact of the party's Peppol ID (INV-REQ-318): the value the query names when it names the scheme
     * `PEPPOL_PARTICIPANT_ID`, else the Peppol identifier the counterparty holds; none when the party has no Peppol ID,
     * as there is nothing to look up. The fact is returned, never a submission type (INV-AC-9499).
     */
    private fun reachabilityOf(
        entityRef: String,
        named: NamedParty,
        answer: PartyAnswer?,
    ): Reachability? {
        val held =
            (answer as? PartyAnswer.Resolved)
                ?.version
                ?.content
                ?.identifiers
                ?.firstOrNull { it.scheme == IdentifierScheme.PEPPOL_PARTICIPANT_ID }
                ?.value
        val peppolId =
            named.partyValue.takeIf { named.partyScheme == IdentifierScheme.PEPPOL_PARTICIPANT_ID.name } ?: held
        return peppolId?.let {
            val fact = reachability.handle(entityRef, it)
            Reachability(fact.onNetwork, fact.degradationCode)
        }
    }

    /** The query parameters that name a party: a reference, or a scheme and value pair. */
    private data class NamedParty(
        val partyRef: String?,
        val partyScheme: String?,
        val partyValue: String?,
    )

    /** The answer for the party the query names; null when it names none. */
    private fun answerFor(
        entityRef: String,
        named: NamedParty,
        transactionDate: LocalDate,
    ): PartyAnswer? {
        val (partyRef, partyScheme, partyValue) = named
        val byRef =
            partyRef?.let { PartyKey.ByRef(runCatching { PartyRef(it) }.getOrElse { invalid("partyRef is invalid") }) }
        if (byRef == null && (partyScheme == null || partyValue == null)) return null
        // a scheme this service does not hold is a party it cannot find: unresolved (MDM-001), not a refusal
        val scheme = IdentifierScheme.entries.firstOrNull { it.name == partyScheme }
        val key = byRef ?: scheme?.let { PartyKey.ByIdentifier(it, checkNotNull(partyValue)) }
        return key?.let { resolveParty.handle(PartyQuestion(entityRef, it, transactionDate)) }
            ?: PartyAnswer.Unresolved()
    }

    override fun getCounterpartyHistory(
        counterpartyRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<CounterpartyHistory> {
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { throw ApiRefusal(ClosedErrorCode.NOT_FOUND) }
        val after =
            if (cursor ==
                null
            ) {
                0L
            } else {
                cursor.toLongOrNull()?.takeIf { it >= 0 } ?: invalid("cursor is invalid")
            }
        if (limit < 1) invalid("limit is invalid")
        val page = history.handle(party, after, limit) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(
            CounterpartyHistory(
                items =
                    page.versions.map {
                        CounterpartyVersion(
                            version = it.version.toInt(),
                            recordedAt = it.recordedAt.atOffset(ZoneOffset.UTC),
                            current = it.version == page.current,
                            counterparty = CounterpartyMapping.counterparty(it, it.content.schemes),
                        )
                    },
                nextCursor = page.nextAfter?.toString(),
            ),
        )
    }

    /**
     * The write path of the ingestion counterparty object (INV-Q-8486 A): stores what canonical-svc supplies as a new
     * version and never validates it as a precondition (the `must_not` of this service). `MIGRATED` is not a member of
     * the request enum, so the contract refuses it with 400 before this runs.
     */
    override fun recordIngestedCounterparty(
        counterpartyRef: String,
        idempotencyKey: UUID,
        ingestedCounterpartyRequest: IngestedCounterpartyRequest,
    ): ResponseEntity<Counterparty> {
        val request = ingestedCounterpartyRequest
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { invalid("counterpartyRef is invalid") }
        val entity = request.entityRef.trim().takeIf { it.isNotEmpty() } ?: invalid("entityRef must not be blank")
        val origin =
            when (request.providedBy) {
                IngestedCounterpartyRequest.ProvidedBy.COUNTERPARTY -> WriteOrigin.COUNTERPARTY_LINK
                IngestedCounterpartyRequest.ProvidedBy.INTERNAL -> WriteOrigin.OPERATOR
            }
        val change =
            CounterpartyChangeMapping.change(
                request.resolvedIdentifiers,
                request.legalName,
                request.address,
                request.arcsGstChargeAgreed,
                request.approvedSchemes,
            )
        val version =
            record.handle(
                RecordCommand(entity, party, change, CounterpartyChangeMapping.evidence(request.evidenceRef), origin),
            )
        return ResponseEntity.ok(CounterpartyMapping.counterparty(version, version.content.schemes))
    }

    /**
     * `correctCounterpartyData` (INV-T-27119, INV-REQ-886): the entity outside the principal's set is refused 403
     * SRT-004 by the runtime's gate before this method. The permission `privacy-request.handle` is decided per request
     * through the replica (INV-Q-10027) before the key is looked at, so a replay never outlives the grant. The
     * Idempotency-Key is handled by the shared [IdempotencyStore] (API-15): the version, the audit entry and the key
     * record commit or roll back in one transaction; a replay answers the stored version, the same key with another
     * body is 422 SRT-009 and a key in flight is 409 SRT-007. evidence-svc is never called.
     */
    override fun correctCounterpartyData(
        counterpartyRef: String,
        idempotencyKey: UUID,
        correctCounterpartyDataRequest: CorrectCounterpartyDataRequest,
    ): ResponseEntity<CounterpartyVersion> {
        val request = correctCounterpartyDataRequest
        val caller = permitted()
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { invalid("counterpartyRef is invalid") }
        val entity = request.entityRef.trim().takeIf { it.isNotEmpty() } ?: invalid("entityRef must not be blank")
        val field = correctableField(request.field.value) ?: invalid("field is not correctable")
        val command = CorrectionCommand(entity, party, request.caseId, field, request.correctedValue, caller)
        val requestLine = "POST $CORRECTIONS_PATH_PREFIX$counterpartyRef$CORRECTIONS_PATH_SUFFIX"
        val fingerprint = fingerprint(mapper, requestLine, request)
        val outcome =
            tx.inTransaction {
                idempotency.getObject().once(tenants.current(), caller, idempotencyKey, fingerprint) { _ ->
                    StoredResponse(HttpStatus.OK.value(), mapper.writeValueAsString(apply(command, field)))
                }
            }
        val stored =
            when (outcome) {
                is IdempotencyOutcome.Fresh -> outcome.response
                is IdempotencyOutcome.Replay -> outcome.response
                IdempotencyOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
                IdempotencyOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
            }
        return ResponseEntity.ok(mapper.readValue(stored.body, CounterpartyVersion::class.java))
    }

    /** The correction and its audit entry, inside the transaction of the key record; a refusal rolls both back. */
    private fun apply(
        command: CorrectionCommand,
        field: CorrectableField,
    ): CounterpartyVersion =
        when (val result = correct.handle(command)) {
            is CorrectionResult.Applied -> {
                correctionAudit.corrected(
                    command.actor,
                    command.entity,
                    command.party.value,
                    command.caseId,
                    field,
                )
                val version = result.version
                CounterpartyVersion(
                    version = version.version.toInt(),
                    recordedAt = version.recordedAt.atOffset(ZoneOffset.UTC),
                    current = true,
                    counterparty = CounterpartyMapping.counterparty(version, version.content.schemes),
                )
            }

            CorrectionResult.UnknownCounterparty -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            is CorrectionResult.Refused -> {
                invalid(result.reason)
            }
        }

    /** The acting user ID, once `privacy-request.handle` is decided for it through the replica (INV-Q-10027). */
    private fun permitted(): String {
        val caller = tenants.currentActor()?.takeIf { it.isNotBlank() }
        if (caller == null || !permission.holds(caller, PRIVACY_REQUEST_HANDLE)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        return caller
    }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

    companion object {
        /** `x-operation.permission` of `correctCounterpartyData` (INV-REQ-886), decided per request by the replica. */
        const val PRIVACY_REQUEST_HANDLE = "privacy-request.handle"
        private const val CORRECTIONS_PATH_PREFIX = "/internal/v1/masterdata/counterparties/"
        private const val CORRECTIONS_PATH_SUFFIX = "/corrections"
    }
}

/** SHA-256 of the request line, a newline and the JSON bytes of the body (API-15). */
private fun fingerprint(
    mapper: ObjectMapper,
    requestLine: String,
    body: CorrectCounterpartyDataRequest,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("$requestLine\n".toByteArray(StandardCharsets.UTF_8))
    digest.update(mapper.writeValueAsBytes(body))
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** The contract `field` value to the correctable field, by equality; null for any other value. */
private fun correctableField(value: String): CorrectableField? =
    when (value) {
        "legalName" -> CorrectableField.LEGAL_NAME
        "addressLine1" -> CorrectableField.ADDRESS_LINE_1
        "addressLine2" -> CorrectableField.ADDRESS_LINE_2
        "postalCode" -> CorrectableField.POSTAL_CODE
        else -> null
    }
