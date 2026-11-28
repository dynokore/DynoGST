package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.gapreport.LongOutcome
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** The election in force on a date: the rate source and the basis it is elected for. */
data class ElectedRate(
    val source: String,
    val basis: String,
)

/** The rate election of an entity on a date; implemented by INV-T-18775. Null when none is in force on that date. */
fun interface ElectedRateSource {
    fun elected(
        entity: String,
        date: LocalDate,
    ): ElectedRate?
}

/** The approved rate sources for a basis on a date; implemented by INV-T-18775. */
fun interface ApprovedRateSources {
    fun isApproved(
        source: String,
        basis: String,
        date: LocalDate,
    ): Boolean
}

/** What a successful load tells the world: the source, the currency pairs and the date range (INV-REQ-506). */
data class ReferenceRateLoaded(
    val source: String,
    val currencyPairs: Set<String>,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val importId: String,
)

/** Requests the `ReferenceRateLoaded` event; the publish itself is wired by INV-T-18775. */
fun interface ReferenceRateLoadedPublisher {
    fun publish(event: ReferenceRateLoaded)
}

/** One mapped row of a rate file; [rate] is the text as mapped and is validated here. */
data class RateRow(
    val row: Int,
    val baseCurrency: String,
    val quoteCurrency: String,
    val date: LocalDate,
    val rate: String,
)

/** Why a row of a rate file was rejected; the wire codes come from the error catalogue, mapped by INV-T-18775. */
enum class RateRowRejection { CURRENCY_INVALID, RATE_INVALID, REVISION_CONFLICT }

data class RejectedRateRow(
    val row: Int,
    val reason: RateRowRejection,
    val field: String,
)

/** The parts of a rate-file request the validation reads; the dry run and the apply send the same ones. */
interface RateFile {
    val entity: String
    val source: String
    val rangeFrom: LocalDate
    val rowCount: Int
    val rows: List<RateRow>
}

data class ImportRatesCommand(
    override val entity: String,
    /** The token's principal, never a request field. */
    val actor: String,
    override val source: String,
    override val rangeFrom: LocalDate,
    val rangeTo: LocalDate,
    val importId: String,
    val fileHash: String,
    override val rowCount: Int,
    override val rows: List<RateRow>,
) : RateFile

/** Why a whole rate file is refused. */
internal enum class RateFileRefusal { SOURCE_NOT_ELECTED, SOURCE_NOT_APPROVED, ROW_COUNT_MISMATCH }

/** One row after the currency and rate rules: [Valid] with its parsed rate, or [Rejected]. */
internal sealed interface CheckedRateRow {
    data class Valid(
        val rate: BigDecimal,
    ) : CheckedRateRow

    data class Rejected(
        val rejected: RejectedRateRow,
    ) : CheckedRateRow
}

/** The one validation of a rate file, shared by the dry run and the apply (INV-REQ-506). Reads only. */
internal class RateFileRules(
    private val elected: ElectedRateSource,
    private val approved: ApprovedRateSources,
) {
    /** The election, approval and row-count rules; null when the file as a whole is accepted. */
    fun refusal(file: RateFile): RateFileRefusal? {
        // Point-in-time: each distinct row date (the range start when there are no rows) is judged by its own election.
        val dates =
            file.rows
                .map { it.date }
                .distinct()
                .ifEmpty { listOf(file.rangeFrom) }
        val elections = dates.associateWith { elected.elected(file.entity, it) }
        return if (elections.values.any { it == null || it.source != file.source }) {
            RateFileRefusal.SOURCE_NOT_ELECTED
        } else if (elections.any { (date, election) -> !isApproved(file.source, election, date) }) {
            RateFileRefusal.SOURCE_NOT_APPROVED
        } else if (file.rows.size != file.rowCount) {
            RateFileRefusal.ROW_COUNT_MISMATCH
        } else {
            null
        }
    }

    /** The currency and rate rules of one row. */
    fun check(row: RateRow): CheckedRateRow {
        val rate = row.rate.trim().toBigDecimalOrNull()
        return if (!CURRENCY.matches(row.baseCurrency)) {
            rejected(row, RateRowRejection.CURRENCY_INVALID, "baseCurrency")
        } else if (!CURRENCY.matches(row.quoteCurrency)) {
            rejected(row, RateRowRejection.CURRENCY_INVALID, "quoteCurrency")
        } else if (rate == null || rate.signum() <= 0) {
            rejected(row, RateRowRejection.RATE_INVALID, "rate")
        } else {
            CheckedRateRow.Valid(rate)
        }
    }

    private fun rejected(
        row: RateRow,
        reason: RateRowRejection,
        field: String,
    ) = CheckedRateRow.Rejected(RejectedRateRow(row.row, reason, field))

    private fun isApproved(
        source: String,
        election: ElectedRate?,
        date: LocalDate,
    ) = election != null && approved.isApproved(source, election.basis, date)

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

sealed interface ImportRatesOutcome {
    data class Loaded(
        val stored: Int,
        val rejected: List<RejectedRateRow>,
        val record: RateImportRecord,
    ) : ImportRatesOutcome

    /** The work outlasted the wait; [record] is the RUNNING record the caller reads later. */
    data class Running(
        val record: RateImportRecord,
    ) : ImportRatesOutcome

    /** No source is elected, or the request source is not exactly the elected one. Nothing stored. */
    data object SourceNotElected : ImportRatesOutcome

    /** The source is not approved for the basis on the date. Nothing stored. */
    data object SourceNotApproved : ImportRatesOutcome

    /** The mapped rows do not number `rowCount`. Nothing stored. */
    data object RowCountMismatch : ImportRatesOutcome
}

/**
 * INV-REQ-506: file import of rates. The source must equal (exact string equality) the elected source and be approved
 * for the basis on the date, else the request is refused and stores 0. A row count different from `rowCount` is
 * refused. A row with an invalid rate (or currency) is listed as rejected and the others are stored (the rule of
 * the counterparty import). One `ReferenceRateLoaded` is requested per load that stored records, after the commit.
 */
@Suppress("LongParameterList") // the ports of one use case
class ImportRates(
    private val tx: TransactionRunner,
    private val store: RateStore,
    elected: ElectedRateSource,
    approved: ApprovedRateSources,
    private val publisher: ReferenceRateLoadedPublisher,
    private val imports: RateImportStore,
    private val clock: Clock,
    private val runner: LongRunner,
) {
    private val rules = RateFileRules(elected, approved)

    fun handle(command: ImportRatesCommand): ImportRatesOutcome {
        val refusal = rules.refusal(command)
        if (refusal != null) return outcomeOf(refusal)
        val running = tx.inTransaction { startRecord(command) }
        val done = runner.run { work(command) }
        return when (done) {
            is LongOutcome.StillRunning -> {
                ImportRatesOutcome.Running(running)
            }

            is LongOutcome.Finished -> {
                val record = checkNotNull(tx.inTransaction { imports.find(command.importId) })
                ImportRatesOutcome.Loaded(done.value.first, done.value.second, record)
            }
        }
    }

    private fun outcomeOf(refusal: RateFileRefusal) =
        when (refusal) {
            RateFileRefusal.SOURCE_NOT_ELECTED -> ImportRatesOutcome.SourceNotElected
            RateFileRefusal.SOURCE_NOT_APPROVED -> ImportRatesOutcome.SourceNotApproved
            RateFileRefusal.ROW_COUNT_MISMATCH -> ImportRatesOutcome.RowCountMismatch
        }

    private fun startRecord(command: ImportRatesCommand) =
        imports.insertRunning(
            command.importId,
            command.entity,
            command.actor,
            command.source,
            command.rangeFrom,
            command.rangeTo,
        )

    /** The load and its record finish in one transaction; a throw marks the record FAILED and propagates. */
    private fun work(command: ImportRatesCommand): Pair<Int, List<RejectedRateRow>> {
        val (stored, rejected) =
            try {
                tx.inTransaction {
                    val result = load(command)
                    imports.finish(
                        command.importId,
                        clock.instant(),
                        result.first.size,
                        result.second.size,
                        result.second,
                    )
                    result
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                tx.inTransaction { imports.fail(command.importId) }
                throw e
            }
        publishLoaded(command, stored)
        return stored.size to rejected
    }

    private fun publishLoaded(
        command: ImportRatesCommand,
        stored: List<ExchangeRate>,
    ) {
        if (stored.isEmpty()) return
        publisher.publish(
            ReferenceRateLoaded(
                command.source,
                stored.map { "${it.baseCurrency}/${it.quoteCurrency}" }.toSortedSet(),
                stored.minOf { it.date },
                stored.maxOf { it.date },
                command.importId,
            ),
        )
    }

    private fun load(command: ImportRatesCommand): Pair<List<ExchangeRate>, List<RejectedRateRow>> {
        val stored = mutableListOf<ExchangeRate>()
        val rejected = mutableListOf<RejectedRateRow>()
        for (row in command.rows) {
            when (val checked = rules.check(row)) {
                is CheckedRateRow.Rejected -> {
                    rejected += checked.rejected
                }

                is CheckedRateRow.Valid -> {
                    val record = record(command, row, checked.rate)
                    if (store.save(record) == SaveResult.Saved) {
                        stored += record
                    } else {
                        rejected += RejectedRateRow(row.row, RateRowRejection.REVISION_CONFLICT, "rate")
                    }
                }
            }
        }
        return stored to rejected
    }

    private fun record(
        command: ImportRatesCommand,
        row: RateRow,
        rate: BigDecimal,
    ): ExchangeRate {
        val previous = store.latest(command.source, row.baseCurrency, row.quoteCurrency, row.date)
        return ExchangeRate(
            source = command.source,
            baseCurrency = row.baseCurrency,
            quoteCurrency = row.quoteCurrency,
            date = row.date,
            rate = rate,
            provenance = Provenance.IMPORT,
            evidenceRef = null,
            revision = (previous?.revision ?: 0) + 1,
            importId = command.importId,
        )
    }
}
