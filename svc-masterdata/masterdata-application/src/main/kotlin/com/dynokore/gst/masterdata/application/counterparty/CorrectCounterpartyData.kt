package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** The stored personal-data fields a privacy correction may change (INV-REQ-886). */
enum class CorrectableField(
    val maxLength: Int,
) {
    LEGAL_NAME(MAX_TEXT),
    ADDRESS_LINE_1(MAX_TEXT),
    ADDRESS_LINE_2(MAX_TEXT),
    POSTAL_CODE(MAX_POSTAL_CODE),
}

private const val MAX_TEXT = 200
private const val MAX_POSTAL_CODE = 20

/** One correction of [field] of [party] on behalf of [entity], for the privacy case [caseId], made by [actor]. */
data class CorrectionCommand(
    val entity: String,
    val party: PartyRef,
    val caseId: UUID,
    val field: CorrectableField,
    val value: String,
    val actor: String,
)

sealed interface CorrectionResult {
    data class Applied(
        val version: CounterpartyVersion,
    ) : CorrectionResult

    data object UnknownCounterparty : CorrectionResult

    data class Refused(
        val reason: String,
    ) : CorrectionResult
}

/**
 * INV-REQ-886 (INV-AC-27115): corrects one stored field of a counterparty going forward by appending one version
 * through [RecordCounterparty]; only the named field changes and no earlier version is touched. The evidence reference
 * is `privacy-case:<caseId>`; nothing of evidence-svc is read or written.
 */
class CorrectCounterpartyData internal constructor(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val write: (RecordCommand) -> CounterpartyVersion,
) {
    constructor(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        record: RecordCounterparty,
    ) : this(tx, parties, record::handle)

    fun handle(command: CorrectionCommand): CorrectionResult =
        tx.inTransaction {
            val current = parties.current(command.party) ?: return@inTransaction CorrectionResult.UnknownCounterparty
            val value = command.value.trim()
            val refusal = refusal(command.field, value, current.content.address)
            if (refusal != null) return@inTransaction CorrectionResult.Refused(refusal)
            val change =
                CounterpartyChange(
                    identifiers = emptyList(),
                    legalName = value.takeIf { command.field == CorrectableField.LEGAL_NAME },
                    address =
                        current.content.address
                            ?.let { merged(it, command.field, value) }
                            .takeIf { command.field != CorrectableField.LEGAL_NAME },
                    arcsGstChargeAgreed = null,
                    approvedSchemes = null,
                )
            val written =
                write(
                    RecordCommand(
                        command.entity,
                        command.party,
                        change,
                        "privacy-case:" + command.caseId.toString().lowercase(),
                        WriteOrigin.OPERATOR,
                    ),
                )
            CorrectionResult.Applied(written)
        }

    private fun refusal(
        field: CorrectableField,
        value: String,
        address: PostalAddress?,
    ): String? =
        when {
            value.isEmpty() -> "the value is blank"
            value.length > field.maxLength -> "the value is longer than ${field.maxLength} characters"
            field != CorrectableField.LEGAL_NAME && address == null -> "the counterparty holds no address"
            else -> null
        }

    private fun merged(
        held: PostalAddress,
        field: CorrectableField,
        value: String,
    ): PostalAddress =
        when (field) {
            CorrectableField.ADDRESS_LINE_1 -> held.copy(line1 = value)
            CorrectableField.ADDRESS_LINE_2 -> held.copy(line2 = value)
            CorrectableField.POSTAL_CODE -> held.copy(postalCode = value)
            CorrectableField.LEGAL_NAME -> held
        }
}
