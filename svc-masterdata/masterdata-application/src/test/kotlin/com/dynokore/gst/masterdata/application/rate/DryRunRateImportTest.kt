package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DryRunRateImportTest {
    private val day = LocalDate.parse("2026-11-03")
    private val next = day.plusDays(1)
    private val tenantA = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private var bound = tenantA
    private var tenantDown = false
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId {
                check(!tenantDown) { "no tenant bound" }
                return bound
            }

            override fun currentActor() = "IDN-0001"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ) = block()
        }
    private var txDown = false
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                check(!txDown) { "no transaction" }
                return block()
            }
        }
    private val rates = CountingRateStore()
    private val dryRuns = FakeRateDryRunStore()
    private val events = mutableListOf<ReferenceRateLoaded>()
    private var election: ElectedRate? = ElectedRate("MAS", "DAILY")
    private var approval = true
    private val elected = ElectedRateSource { _, _ -> election }
    private val approved = ApprovedRateSources { _, _, _ -> approval }
    private val dryRun = dryRunWith(directRunner())
    private val rule = ReviewedRateDryRun(tx, dryRuns, tenants)

    private fun dryRunWith(runner: LongRunner) =
        DryRunRateImport(tx, rates, elected, approved, dryRuns, tenants, runner)

    private fun row(
        n: Int,
        base: String,
        rate: String,
        date: LocalDate = day,
        quote: String = "SGD",
    ) = RateRow(n, base, quote, date, rate)

    private val rows = listOf(row(1, "USD", "1.35"), row(2, "EUR", "1.47"), row(3, "GBP", "1.71", next))

    private fun command(
        rows: List<RateRow> = this.rows,
        count: Int = rows.size,
        source: String = "MAS",
    ) = DryRunRateImportCommand("E1", "IDN-0001", source, day, "sha256:abc", count, rows)

    private fun apply(
        rows: List<RateRow> = this.rows,
        entity: String = "E1",
        source: String = "MAS",
        fileHash: String = "sha256:abc",
    ) = ImportRatesCommand(entity, "IDN-0001", source, day, next, "IMP-1", fileHash, rows.size, rows)

    private fun recorded(outcome: DryRunRateImportOutcome) =
        assertInstanceOf(DryRunRateImportOutcome.Recorded::class.java, outcome)

    private fun succeeded(): String = recorded(dryRun.handle(command())).record.dryRunId

    private fun assertNoRateAndNoEvent() {
        assertEquals(0, rates.saves)
        assertEquals(0, events.size)
    }

    private fun stored(
        base: String,
        rate: String,
        revision: Int,
        source: String = "MAS",
        date: LocalDate = day,
    ) = ExchangeRate(source, base, "SGD", date, BigDecimal(rate), Provenance.IMPORT, null, revision, "IMP-0")

    @Test
    fun `INV-AC-28560 a dry run saves no rate and publishes no event, and keeps rows and result`() {
        rates.held += stored("USD", "1.30", 1)
        rates.held += stored("USD", "1.31", 2)
        rates.held += stored("EUR", "1.40", 1, source = "ECB")
        rates.held += stored("GBP", "1.60", 1, date = day)

        val out = recorded(dryRun.handle(command()))

        assertNoRateAndNoEvent()
        assertTrue(out.finished)
        val record = out.record
        assertEquals(RecordState.SUCCEEDED, record.state)
        assertEquals(
            listOf(tenantA, "E1", "IDN-0001", "MAS", "sha256:abc", 3),
            listOf(record.tenant, record.entity, record.actor, record.source, record.fileHash, record.rowCount),
        )
        assertEquals(rows, record.rows)
        assertTrue(record.dryRunId.startsWith("RDR-") && record.dryRunId.length <= 40, record.dryRunId)
        val result = checkNotNull(record.result)
        assertEquals(emptyList<RejectedRateRow>(), result.rejected)
        // USD supersedes the newest revision; the ECB rate and the rate of another date supersede nothing.
        assertEquals(
            listOf(
                RateChange("USD", "SGD", day, BigDecimal("1.31"), BigDecimal("1.35")),
                RateChange("EUR", "SGD", day, null, BigDecimal("1.47")),
                RateChange("GBP", "SGD", next, null, BigDecimal("1.71")),
            ),
            result.changes,
        )
        assertEquals(listOf(3, 2, 1), listOf(result.validCount, result.createCount, result.supersedeCount))
    }

    @Test
    fun `INV-AC-28560 the dry run rejects the rows the apply rejects and previews what the apply stores`() {
        val file =
            listOf(
                row(1, "usd", "1.35"),
                row(2, "EUR", "abc"),
                row(3, "GBP", "0"),
                row(4, "CHF", "-1"),
                row(5, "JPY", " 0.009 "),
                row(6, "AUD", "0.88", quote = "sgd"),
                row(7, "NZD", "0.80"),
                row(8, "NZD", "0.81"),
                row(9, "NZD", ""),
            )
        val result = checkNotNull(recorded(dryRun.handle(command(file))).record.result)
        assertNoRateAndNoEvent()

        val importer =
            ImportRates(
                tx,
                rates,
                elected,
                approved,
                { events += it },
                FakeRateImportStore(),
                Clock.fixed(Instant.parse("2026-11-09T01:00:00Z"), ZoneOffset.UTC),
                directRunner(),
            )
        val loaded = assertInstanceOf(ImportRatesOutcome.Loaded::class.java, importer.handle(apply(file)))

        assertEquals(listOf(1, 2, 3, 4, 6, 9), result.rejected.map { it.row })
        assertEquals(loaded.rejected, result.rejected)
        assertEquals(loaded.stored, result.validCount)
        assertEquals(rates.held.map { it.rate }, result.changes.map { it.newRate })
        assertEquals(listOf(null, null, BigDecimal("0.80")), result.changes.map { it.oldRate })
        assertEquals(listOf(2, 1), listOf(result.createCount, result.supersedeCount))
    }

    @Test
    fun `INV-AC-28560 a refused file records no dry run`() {
        fun assertRefused(
            expected: DryRunRateImportOutcome,
            command: DryRunRateImportCommand = command(),
        ) {
            assertEquals(expected, dryRun.handle(command))
            assertTrue(dryRuns.records.isEmpty())
            assertNoRateAndNoEvent()
        }
        for (lookAlike in listOf("mas", "MAS ", "MAS2")) {
            assertRefused(DryRunRateImportOutcome.SourceNotElected, command(source = lookAlike))
        }
        assertRefused(DryRunRateImportOutcome.RowCountMismatch, command(count = 2))
        assertRefused(DryRunRateImportOutcome.RowCountMismatch, command(count = 4))
        approval = false
        assertRefused(DryRunRateImportOutcome.SourceNotApproved)
        election = null
        assertRefused(DryRunRateImportOutcome.SourceNotElected)
        assertRefused(DryRunRateImportOutcome.SourceNotElected, command(emptyList()))
    }

    @Test
    fun `INV-AC-28560 each row date is judged by its own election and approval`() {
        val asked = mutableListOf<LocalDate>()
        val perDate =
            DryRunRateImport(
                tx,
                rates,
                { _, date -> ElectedRate(if (date == next) "MAS2" else "MAS", "DAILY") },
                { _, _, date ->
                    asked += date
                    true
                },
                dryRuns,
                tenants,
                directRunner(),
            )
        assertEquals(DryRunRateImportOutcome.SourceNotElected, perDate.handle(command()))
        recorded(perDate.handle(command(listOf(row(1, "USD", "1.35")))))
        assertEquals(listOf(day), asked)
    }

    @Test
    fun `INV-AC-28560 the apply rule accepts a succeeded dry run equal to the request`() {
        val id = succeeded()
        assertTrue(rule.permits(id, apply()))
        assertNoRateAndNoEvent()
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses an unknown, missing or blank id`() {
        val id = succeeded()
        for (other in listOf(null, "", "  ", "RDR-unknown", "$id ", id.lowercase(), id.dropLast(1))) {
            assertFalse(rule.permits(other, apply()), "'$other'")
        }
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses another tenant, entity, source or fileHash`() {
        val id = succeeded()
        for (entity in listOf("E2", "e1", "E1 ", "E", "")) {
            assertFalse(rule.permits(id, apply(entity = entity)), "entity '$entity'")
        }
        for (source in listOf("ECB", "mas", "MAS ", "MAS2")) {
            assertFalse(rule.permits(id, apply(source = source)), "source '$source'")
        }
        for (hash in listOf("sha256:abd", "sha256:ABC", "sha256:abc ", "sha256:ab", "abc", "*", "")) {
            assertFalse(rule.permits(id, apply(fileHash = hash)), "fileHash '$hash'")
        }
        // A store that fails to confine its read to the bound tenant is still refused.
        bound = tenantB
        assertFalse(rule.permits(id, apply()))
        bound = tenantA
        assertTrue(rule.permits(id, apply()))
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses changed, reordered, dropped or added rows`() {
        val id = succeeded()
        val (first, second, third) = rows
        val lookAlikes =
            mapOf(
                "one changed rate" to listOf(first, second.copy(rate = "1.48"), third),
                "the same rate written otherwise" to listOf(first, second.copy(rate = "1.470"), third),
                "one padded rate" to listOf(first, second.copy(rate = "1.47 "), third),
                "one changed base currency" to listOf(first, second.copy(baseCurrency = "CHF"), third),
                "one changed quote currency" to listOf(first, second.copy(quoteCurrency = "USD"), third),
                "one changed date" to listOf(first, second.copy(date = next), third),
                "one changed row number" to listOf(first, second.copy(row = 9), third),
                "reordered" to listOf(second, first, third),
                "dropped" to listOf(first, second),
                "added" to rows + row(4, "JPY", "0.009"),
                "repeated" to rows + third,
                "none" to emptyList(),
            )
        lookAlikes.forEach { (name, other) -> assertFalse(rule.permits(id, apply(other)), name) }
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses a failed dry run`() {
        rates.down = true
        assertThrows<IllegalStateException> { dryRun.handle(command()) }
        val record = dryRuns.records.values.single()
        assertEquals(RecordState.FAILED, record.state)
        assertFalse(rule.permits(record.dryRunId, apply()))
        assertNoRateAndNoEvent()
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses a running dry run and accepts it once it succeeded`() {
        val release = CountDownLatch(1)
        rates.gate = release
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slow = dryRunWith(LongRunner(tenants, executor, Duration.ofMillis(50)))
            val out = recorded(slow.handle(command()))
            assertFalse(out.finished)
            assertEquals(RecordState.RUNNING, out.record.state)
            assertFalse(rule.permits(out.record.dryRunId, apply()))
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(rule.permits(out.record.dryRunId, apply()))
            assertNoRateAndNoEvent()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `INV-AC-28560 the apply rule requires the state SUCCEEDED and the id it asked for`() {
        val id = succeeded()
        val good = dryRuns.records.getValue(id)
        for (state in RecordState.entries) {
            dryRuns.records[id] = good.copy(state = state)
            assertEquals(state == RecordState.SUCCEEDED, rule.permits(id, apply()), state.name)
        }
        dryRuns.records[id] = good.copy(dryRunId = "RDR-other")
        assertFalse(rule.permits(id, apply()))
    }

    @Test
    fun `INV-AC-28560 the apply rule refuses on an error of the store, the transaction or the tenant binding`() {
        val id = succeeded()
        dryRuns.down = true
        assertFalse(rule.permits(id, apply()))
        dryRuns.down = false
        txDown = true
        assertFalse(rule.permits(id, apply()))
        txDown = false
        tenantDown = true
        assertFalse(rule.permits(id, apply()))
        tenantDown = false
        assertTrue(rule.permits(id, apply()))
    }

    /** Counts every save; [down] fails the reads and [gate] holds them back. */
    private class CountingRateStore : RateStore {
        val held = mutableListOf<ExchangeRate>()
        var saves = 0
        var down = false
        var gate: CountDownLatch? = null

        override fun latest(
            source: String,
            baseCurrency: String,
            quoteCurrency: String,
            date: LocalDate,
        ): ExchangeRate? {
            gate?.let { check(it.await(10, TimeUnit.SECONDS)) { "never released" } }
            check(!down) { "rate store down" }
            return history(source, baseCurrency, quoteCurrency, date).maxByOrNull { it.revision }
        }

        override fun history(
            source: String,
            baseCurrency: String,
            quoteCurrency: String,
            date: LocalDate,
        ) = held.filter {
            it.source == source && it.baseCurrency == baseCurrency && it.quoteCurrency == quoteCurrency &&
                it.date == date
        }

        override fun inForce(
            source: String,
            baseCurrency: String,
            quoteCurrency: String,
            onDate: LocalDate,
        ): ExchangeRate? = error("a dry run and an apply read the record of the row date only")

        override fun save(rate: ExchangeRate): SaveResult {
            saves++
            held += rate
            return SaveResult.Saved
        }
    }

    /** In-memory [RateDryRunStore]; it does not filter by tenant, so the tests see what the rule itself refuses. */
    private class FakeRateDryRunStore : RateDryRunStore {
        val records = linkedMapOf<String, RateDryRunRecord>()
        var down = false

        override fun insertRunning(
            dryRunId: String,
            tenant: TenantId,
            entity: String,
            actor: String,
            source: String,
            fileHash: String,
            rows: List<RateRow>,
        ) = RateDryRunRecord(
            dryRunId,
            tenant,
            entity,
            actor,
            source,
            fileHash,
            rows.size,
            rows.toList(),
            RecordState.RUNNING,
            Instant.parse("2026-11-09T00:00:00Z"),
            null,
            null,
            null,
        ).also { records[dryRunId] = it }

        override fun finish(
            dryRunId: String,
            result: RateDryRunResult,
        ) {
            records[dryRunId] = records.getValue(dryRunId).copy(state = RecordState.SUCCEEDED, result = result)
        }

        override fun fail(dryRunId: String) {
            records[dryRunId] = records.getValue(dryRunId).copy(state = RecordState.FAILED, error = "failed")
        }

        override fun find(dryRunId: String): RateDryRunRecord? {
            check(!down) { "dry run store down" }
            return records[dryRunId]
        }

        override fun failRunningBefore(before: Instant) = 0
    }
}
