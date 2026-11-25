package com.dynokore.gst.evidence.application.migration

import com.dynokore.gst.evidence.domain.retention.PriorEvidencePeriodRef
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The one scoped permission key of the import, decided per request on the entity (INV-Q-10027). */
const val PRIOR_EVIDENCE_IMPORT_PERMISSION = "migration-cutover.record"

/** `ImportPriorEvidenceRequest` of the evidence contract. */
data class ImportPriorEvidenceCommand(
    val priorSolution: String,
    val records: List<PriorSubmissionRecordCommand>,
)

/** `PriorSubmissionRecord` of the evidence contract. */
data class PriorSubmissionRecordCommand(
    val documentNumber: String,
    val transactionDate: LocalDate,
    val acknowledgementRef: String,
    val priorDocUuid: UUID? = null,
)

sealed interface ImportResult {
    data class Imported(
        val entityRef: String,
        val priorSolution: String,
        val importedCount: Int,
        val alreadyImportedCount: Int,
        val importedAt: Instant,
        val importedBy: String,
    ) : ImportResult

    data object Forbidden : ImportResult

    /** The request breaks a bound of the contract. */
    data class Rejected(
        val reason: String,
    ) : ImportResult

    /** A record is already imported with a different acknowledgementRef; the whole call is refused. */
    data object Conflict : ImportResult
}

/**
 * Imports prior submission evidence of one entity (INV-REQ-1109 behaviour 1, INV-REQ-1112). It runs in the caller's
 * transaction. Fail closed (OWASP ASVS V1, V4): the permission is decided first, then the contract bounds; then the
 * retention units of all records are locked, before anything is read (one lock order with the retention-end deletion);
 * then every record is classified in request order. Only a call without a conflict inserts rows and requests events.
 * A forbidden or rejected call makes no store call. A call that answers [ImportResult.Conflict] may have created empty
 * units or members, which go only with a rollback: the caller must roll back its transaction (unit of work).
 * Imported evidence is never overwritten.
 */
class ImportPriorEvidence(
    private val authorizer: PriorEvidenceAuthorizer,
    private val store: PriorEvidenceStore,
    private val events: PriorEvidenceEvents,
    private val clock: Clock,
    private val ids: () -> UUID,
) {
    fun execute(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        command: ImportPriorEvidenceCommand,
    ): ImportResult {
        if (!permitted(tenant, principal, entityRef)) return ImportResult.Forbidden
        val breach = breach(entityRef, command)
        return if (breach != null) {
            ImportResult.Rejected(breach)
        } else {
            import(tenant, principal, entityRef, command)
        }
    }

    private fun import(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        command: ImportPriorEvidenceCommand,
    ): ImportResult {
        store.lockUnits(tenant, entityRef, command.records.map { PriorEvidencePeriodRef.of(it.transactionDate) })
        val newRecords = classify(tenant, entityRef, command.records) ?: return ImportResult.Conflict
        val importedAt = clock.instant()
        newRecords.forEach { file(tenant, principal, entityRef, command.priorSolution, it, importedAt) }
        return ImportResult.Imported(
            entityRef = entityRef,
            priorSolution = command.priorSolution,
            importedCount = newRecords.size,
            alreadyImportedCount = command.records.size - newRecords.size,
            importedAt = importedAt,
            importedBy = principal.id,
        )
    }

    @Suppress("TooGenericExceptionCaught") // any failure of the decision is a denial: fail closed
    private fun permitted(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
    ): Boolean =
        try {
            principal.id.isNotBlank() &&
                authorizer.permitted(tenant, principal, entityRef, PRIOR_EVIDENCE_IMPORT_PERMISSION)
        } catch (_: Exception) {
            false
        }

    /** The first broken bound, or null. It reads nothing but the request. */
    private fun breach(
        entityRef: String,
        command: ImportPriorEvidenceCommand,
    ): String? =
        when {
            !within(entityRef, MAX_ENTITY_REF) -> {
                "entityRef must be 1 to $MAX_ENTITY_REF characters"
            }

            !within(command.priorSolution, MAX_PRIOR_SOLUTION) -> {
                "priorSolution must be 1 to $MAX_PRIOR_SOLUTION characters"
            }

            command.records.size !in 1..MAX_RECORDS -> {
                "records must hold 1 to $MAX_RECORDS entries"
            }

            else -> {
                command.records.firstNotNullOfOrNull(::breach)
            }
        }

    private fun breach(record: PriorSubmissionRecordCommand): String? =
        when {
            !within(record.documentNumber, MAX_REF) -> {
                "documentNumber must be 1 to $MAX_REF characters"
            }

            !within(record.acknowledgementRef, MAX_REF) -> {
                "acknowledgementRef must be 1 to $MAX_REF characters"
            }

            record.transactionDate.year !in MIN_YEAR..MAX_YEAR -> {
                "transactionDate year must be $MIN_YEAR to $MAX_YEAR"
            }

            else -> {
                null
            }
        }

    /** Contract lengths count Unicode code points (JSON Schema), not UTF-16 units. */
    private fun within(
        text: String,
        max: Int,
    ): Boolean = text.codePointCount(0, text.length) in 1..max

    /** The new records in request order, or null when any record conflicts. It inserts no row. */
    private fun classify(
        tenant: TenantId,
        entityRef: String,
        records: List<PriorSubmissionRecordCommand>,
    ): List<PriorSubmissionRecordCommand>? {
        val accepted = LinkedHashMap<Pair<String, LocalDate>, PriorSubmissionRecordCommand>()
        for (record in records) {
            val identity = record.documentNumber to record.transactionDate
            store.lock(tenant, entityRef, record.documentNumber, record.transactionDate)
            val stored = store.find(tenant, entityRef, record.documentNumber, record.transactionDate)
            val known = stored?.acknowledgementRef ?: accepted[identity]?.acknowledgementRef
            if (known == null) {
                accepted[identity] = record
            } else if (known != record.acknowledgementRef) {
                return null
            }
        }
        return accepted.values.toList()
    }

    @Suppress("LongParameterList") // the facts of one row, all from this call
    private fun file(
        tenant: TenantId,
        principal: Principal,
        entityRef: String,
        priorSolution: String,
        record: PriorSubmissionRecordCommand,
        importedAt: Instant,
    ) {
        store.insert(
            tenant,
            PriorEvidenceRow(
                id = ids(),
                entityRef = entityRef,
                periodRef = PriorEvidencePeriodRef.of(record.transactionDate),
                documentNumber = record.documentNumber,
                transactionDate = record.transactionDate,
                acknowledgementRef = record.acknowledgementRef,
                priorSolution = priorSolution,
                priorDocUuid = record.priorDocUuid,
                importedAt = importedAt,
                importedBy = principal.id,
            ),
        )
        events.imported(
            tenant,
            PriorEvidenceImported(
                entityRef = entityRef,
                documentNumber = record.documentNumber,
                transactionDate = record.transactionDate,
                acknowledgementRef = record.acknowledgementRef,
                priorSolution = priorSolution,
            ),
        )
    }

    private companion object {
        const val MAX_ENTITY_REF = 40
        const val MAX_PRIOR_SOLUTION = 128
        const val MAX_RECORDS = 500
        const val MAX_REF = 100
        const val MIN_YEAR = 1
        const val MAX_YEAR = 9999
    }
}
