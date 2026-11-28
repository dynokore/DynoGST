package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.gapreport.LongOutcome
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One rate the apply would store; [oldRate] is the rate it would supersede, null when it would create the record. */
data class RateChange(
    val baseCurrency: String,
    val quoteCurrency: String,
    val date: LocalDate,
    val oldRate: BigDecimal?,
    val newRate: BigDecimal,
)

/** The result of a dry run: the rejected rows and the preview, one change per valid row in file order. */
data class RateDryRunResult(
    val rejected: List<RejectedRateRow>,
    val changes: List<RateChange>,
) {
    val validCount: Int get() = changes.size
    val createCount: Int get() = changes.count { it.oldRate == null }
    val supersedeCount: Int get() = changes.count { it.oldRate != null }
}

/** The stored dry run of a rate file (INV-REQ-506): its [rows] are the ones an apply may write. */
data class RateDryRunRecord(
    val dryRunId: String,
    val tenant: TenantId,
    val entity: String,
    /** The user id of the principal that started it. */
    val actor: String,
    val source: String,
    val fileHash: String,
    val rowCount: Int,
    val rows: List<RateRow>,
    val state: RecordState,
    val startedAt: Instant,
    val completedAt: Instant?,
    val result: RateDryRunResult?,
    val error: String?,
)

/**
 * The rate dry runs, kept under tenant and entity; every call runs inside the caller's transaction and sees the bound
 * tenant's records only. The store stamps the times and renders the API-20 envelope of a FAILED record itself.
 */
interface RateDryRunStore {
    @Suppress("LongParameterList") // the columns of the record
    fun insertRunning(
        dryRunId: String,
        tenant: TenantId,
        entity: String,
        actor: String,
        source: String,
        fileHash: String,
        rows: List<RateRow>,
    ): RateDryRunRecord

    fun finish(
        dryRunId: String,
        result: RateDryRunResult,
    )

    fun fail(dryRunId: String)

    fun find(dryRunId: String): RateDryRunRecord?

    /** Finalises FAILED every RUNNING record started before [before]; returns how many. */
    fun failRunningBefore(before: Instant): Int
}

data class DryRunRateImportCommand(
    override val entity: String,
    /** The token's principal, never a request field. */
    val actor: String,
    override val source: String,
    override val rangeFrom: LocalDate,
    val fileHash: String,
    override val rowCount: Int,
    override val rows: List<RateRow>,
) : RateFile

sealed interface DryRunRateImportOutcome {
    /** The dry run is recorded: [record] is finished, or still RUNNING (the caller answers 200 or 202). */
    data class Recorded(
        val record: RateDryRunRecord,
        val finished: Boolean,
    ) : DryRunRateImportOutcome

    /** No source is elected, or the request source is not exactly the elected one. No dry run is recorded. */
    data object SourceNotElected : DryRunRateImportOutcome

    /** The source is not approved for the basis on the date. No dry run is recorded. */
    data object SourceNotApproved : DryRunRateImportOutcome

    /** The mapped rows do not number `rowCount`. No dry run is recorded. */
    data object RowCountMismatch : DryRunRateImportOutcome
}

/**
 * `dryRunRateImport` (INV-REQ-506: upload, then validate and preview, then apply). It applies the rules of
 * [ImportRates] through the same [RateFileRules] and previews each valid row against the stored rates. It stores no
 * rate and publishes no event: it only reads [RateStore] and has no publisher. The RUNNING record is committed before
 * the work, which runs on the bounded executor and finalises it (API-17, INV-Q-17291).
 */
@Suppress("LongParameterList") // the ports of one use case
class DryRunRateImport(
    private val tx: TransactionRunner,
    private val rates: RateStore,
    elected: ElectedRateSource,
    approved: ApprovedRateSources,
    private val dryRuns: RateDryRunStore,
    private val tenants: TenantContext,
    private val runner: LongRunner,
) {
    private val rules = RateFileRules(elected, approved)

    fun handle(command: DryRunRateImportCommand): DryRunRateImportOutcome {
        val refusal = rules.refusal(command)
        if (refusal != null) return outcomeOf(refusal)
        val id = "RDR-" + UUID.randomUUID()
        val tenant = tenants.current()
        val running =
            tx.inTransaction {
                dryRuns.insertRunning(
                    id,
                    tenant,
                    command.entity,
                    command.actor,
                    command.source,
                    command.fileHash,
                    command.rows,
                )
            }
        val outcome = runner.run { work(id, command) }
        return when (outcome) {
            is LongOutcome.StillRunning -> {
                DryRunRateImportOutcome.Recorded(running, false)
            }

            is LongOutcome.Finished -> {
                DryRunRateImportOutcome.Recorded(checkNotNull(tx.inTransaction { dryRuns.find(id) }), true)
            }
        }
    }

    private fun outcomeOf(refusal: RateFileRefusal) =
        when (refusal) {
            RateFileRefusal.SOURCE_NOT_ELECTED -> DryRunRateImportOutcome.SourceNotElected
            RateFileRefusal.SOURCE_NOT_APPROVED -> DryRunRateImportOutcome.SourceNotApproved
            RateFileRefusal.ROW_COUNT_MISMATCH -> DryRunRateImportOutcome.RowCountMismatch
        }

    /** The preview and the finalised record are written in one transaction; a throw marks the record FAILED. */
    private fun work(
        id: String,
        command: DryRunRateImportCommand,
    ) {
        try {
            tx.inTransaction { dryRuns.finish(id, preview(command)) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            tx.inTransaction { dryRuns.fail(id) }
            throw e
        }
    }

    private fun preview(command: DryRunRateImportCommand): RateDryRunResult {
        val rejected = mutableListOf<RejectedRateRow>()
        val changes = mutableListOf<RateChange>()
        // The apply stores row by row, so a later row of the same pair and date supersedes the earlier row of the file.
        val earlier = mutableMapOf<Triple<String, String, LocalDate>, BigDecimal>()
        for (row in command.rows) {
            when (val checked = rules.check(row)) {
                is CheckedRateRow.Rejected -> {
                    rejected += checked.rejected
                }

                is CheckedRateRow.Valid -> {
                    val key = Triple(row.baseCurrency, row.quoteCurrency, row.date)
                    val old =
                        earlier[key]
                            ?: rates.latest(command.source, row.baseCurrency, row.quoteCurrency, row.date)?.rate
                    changes += RateChange(row.baseCurrency, row.quoteCurrency, row.date, old, checked.rate)
                    earlier[key] = checked.rate
                }
            }
        }
        return RateDryRunResult(rejected, changes)
    }
}

/**
 * The apply rule (INV-REQ-506, INV-AC-28560), called by the apply path before it writes anything. It fails closed: it
 * permits an apply only for a stored SUCCEEDED dry run whose tenant, entity, source, fileHash and ordered rows all
 * equal the request, each by exact equality. Everything else, including an error of the store, the transaction or the
 * tenant binding, refuses.
 */
class ReviewedRateDryRun(
    private val tx: TransactionRunner,
    private val dryRuns: RateDryRunStore,
    private val tenants: TenantContext,
) {
    fun permits(
        dryRunId: String?,
        command: ImportRatesCommand,
    ): Boolean {
        if (dryRunId.isNullOrBlank()) return false
        return try {
            val tenant = tenants.current()
            val run = tx.inTransaction { dryRuns.find(dryRunId) }
            run != null && run.state == RecordState.SUCCEEDED && run.matches(dryRunId, tenant, command)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            LOG.log(System.Logger.Level.ERROR, "the reviewed rate dry run could not be read; the apply is refused", e)
            false
        }
    }

    private fun RateDryRunRecord.matches(
        requestedId: String,
        boundTenant: TenantId,
        command: ImportRatesCommand,
    ) = dryRunId == requestedId &&
        tenant == boundTenant &&
        entity == command.entity &&
        source == command.source &&
        fileHash == command.fileHash &&
        rows == command.rows

    private companion object {
        private val LOG = System.getLogger(ReviewedRateDryRun::class.java.name)
    }
}
