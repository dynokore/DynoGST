package com.dynokore.gst.evidence.application.migration

import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ImportPriorEvidenceTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val user = Principal("user-7", PrincipalKind.HUMAN)
    private val start = Instant.parse("2026-11-25T01:02:03Z")
    private val store = RecordingStore()
    private val events = mutableListOf<Pair<TenantId, PriorEvidenceImported>>()
    private val asked = mutableListOf<Asked>()
    private var permit: () -> Boolean = { true }
    private var nextId = 0L
    private val useCase =
        ImportPriorEvidence(
            authorizer = { t, principal, entityRef, permission ->
                asked += Asked(t, principal, entityRef, permission)
                permit()
            },
            store = store,
            events = { t, event -> events += t to event },
            clock = TickingClock(start),
            ids = { UUID(0, ++nextId) },
        )

    private data class Asked(
        val tenant: TenantId,
        val principal: Principal,
        val entityRef: String,
        val permission: String,
    )

    /** Every reading moves one second on, so two readings in one call give two instants. */
    private class TickingClock(
        private var next: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = next.also { next = it.plusSeconds(1) }
    }

    private class RecordingStore : PriorEvidenceStore {
        val calls = mutableListOf<String>()
        val rows = mutableListOf<Pair<TenantId, PriorEvidenceRow>>()

        override fun lock(
            tenant: TenantId,
            entityRef: String,
            documentNumber: String,
            transactionDate: LocalDate,
        ) {
            calls += "lock $tenant $entityRef $documentNumber $transactionDate"
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
            documentNumber: String,
            transactionDate: LocalDate,
        ): StoredPriorEvidence? {
            calls += "find $tenant $entityRef $documentNumber $transactionDate"
            return rows
                .singleOrNull { (t, row) ->
                    t == tenant && row.entityRef == entityRef && row.documentNumber == documentNumber &&
                        row.transactionDate == transactionDate
                }?.let { StoredPriorEvidence(it.second.acknowledgementRef) }
        }

        override fun insert(
            tenant: TenantId,
            row: PriorEvidenceRow,
        ) {
            calls += "insert ${row.documentNumber}"
            rows += tenant to row
        }
    }

    private fun record(
        documentNumber: String,
        date: String = "2026-01-15",
        ack: String = "ACK-$documentNumber",
        priorDocUuid: UUID? = null,
    ) = PriorSubmissionRecordCommand(documentNumber, LocalDate.parse(date), ack, priorDocUuid)

    private fun run(
        vararg records: PriorSubmissionRecordCommand,
        principal: Principal = user,
        entityRef: String = "E1",
        priorSolution: String = "OldSoft",
    ) = useCase.execute(tenant, principal, entityRef, ImportPriorEvidenceCommand(priorSolution, records.toList()))

    private fun imported(
        new: Int,
        already: Int,
        at: Instant = start,
    ) = ImportResult.Imported("E1", "OldSoft", new, already, at, "user-7")

    @Test
    fun `INV-AC-28459 new records are filed under their calendar quarter with one instant and the acting user`() {
        val docUuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

        val result = run(record("D1", "2026-01-15", priorDocUuid = docUuid), record("D2", "2026-12-31"))

        result shouldBe imported(2, 0)
        store.rows shouldBe
            listOf(
                tenant to
                    PriorEvidenceRow(
                        UUID(0, 1),
                        "E1",
                        "2026-Q1",
                        "D1",
                        LocalDate.parse("2026-01-15"),
                        "ACK-D1",
                        "OldSoft",
                        docUuid,
                        start,
                        "user-7",
                    ),
                tenant to
                    PriorEvidenceRow(
                        UUID(0, 2),
                        "E1",
                        "2026-Q4",
                        "D2",
                        LocalDate.parse("2026-12-31"),
                        "ACK-D2",
                        "OldSoft",
                        null,
                        start,
                        "user-7",
                    ),
            )
    }

    @Test
    fun `INV-AC-28459 one event per new record carries the five members in request order`() {
        run(record("D2", "2026-12-31"), record("D1"))

        events shouldBe
            listOf(
                tenant to PriorEvidenceImported("E1", "D2", LocalDate.parse("2026-12-31"), "ACK-D2", "OldSoft"),
                tenant to PriorEvidenceImported("E1", "D1", LocalDate.parse("2026-01-15"), "ACK-D1", "OldSoft"),
            )
        PriorEvidenceImported::class.java.declaredFields
            .map { it.name }
            .toSet() shouldBe
            setOf("entityRef", "documentNumber", "transactionDate", "acknowledgementRef", "priorSolution")
    }

    @Test
    fun `INV-AC-28459 each record is locked before it is read and all are read before the first insert`() {
        run(record("D1"), record("D2"))

        store.calls shouldBe
            listOf(
                "lock $tenant E1 D1 2026-01-15",
                "find $tenant E1 D1 2026-01-15",
                "lock $tenant E1 D2 2026-01-15",
                "find $tenant E1 D2 2026-01-15",
                "insert D1",
                "insert D2",
            )
    }

    @Test
    fun `INV-AC-28459 an equal reference for a stored identity stores nothing and counts as already imported`() {
        run(record("D1"))
        events.clear()

        val result = run(record("D1"))

        result shouldBe imported(0, 1, start.plusSeconds(1))
        store.rows.size shouldBe 1
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28459 another reference for a stored identity refuses the whole call, a new record before it too`() {
        run(record("D1"))
        events.clear()
        store.calls.clear()

        val result = run(record("D2"), record("D1", ack = "OTHER"))

        result shouldBe ImportResult.Conflict
        store.calls.filter { it.startsWith("insert") } shouldBe emptyList()
        store.rows.map { it.second.documentNumber } shouldBe listOf("D1")
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28459 the same identity twice with an equal reference is one new and one already imported`() {
        val result = run(record("D1"), record("D1"))

        result shouldBe imported(1, 1)
        store.rows.size shouldBe 1
        events.size shouldBe 1
    }

    @Test
    fun `INV-AC-28459 the same identity twice with different references is a conflict`() {
        val result = run(record("D1"), record("D1", ack = "OTHER"))

        result shouldBe ImportResult.Conflict
        store.rows shouldBe emptyList()
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28459 the same document number on another date is another record`() {
        val result = run(record("D1", "2026-01-15"), record("D1", "2026-01-16", ack = "OTHER"))

        result shouldBe imported(2, 0)
    }

    @Test
    fun `INV-AC-28459 the permission is asked exactly once with the one key and the entity`() {
        run(record("D1"), record("D2"))

        asked shouldBe listOf(Asked(tenant, user, "E1", "migration-cutover.record"))
    }

    @Test
    fun `INV-AC-28459 a blank acting user is forbidden before any call`() {
        listOf("", "  ").forEach { id ->
            run(record("D1"), principal = Principal(id, PrincipalKind.HUMAN)) shouldBe ImportResult.Forbidden
        }

        asked shouldBe emptyList()
        nothingTouched()
    }

    @Test
    fun `INV-AC-28459 a denied caller reads and stores nothing`() {
        permit = { false }

        run(record("D1")) shouldBe ImportResult.Forbidden

        asked.size shouldBe 1
        nothingTouched()
    }

    @Test
    fun `INV-AC-28459 an authorizer that throws is forbidden`() {
        permit = { error("no decision") }

        run(record("D1")) shouldBe ImportResult.Forbidden

        nothingTouched()
    }

    @Test
    fun `INV-AC-28459 a denied caller with an invalid request is forbidden, not rejected`() {
        permit = { false }

        run() shouldBe ImportResult.Forbidden

        nothingTouched()
    }

    @Test
    fun `INV-AC-28459 no record and 501 records are rejected`() {
        rejected(run())
        rejected(run(*Array(501) { record("D$it") }))
    }

    @Test
    fun `INV-AC-28459 record text outside its bounds is rejected, a later record too`() {
        rejected(run(record("", ack = "A")))
        rejected(run(record("x".repeat(101), ack = "A")))
        rejected(run(record("D1", ack = "")))
        rejected(run(record("D1", ack = "x".repeat(101))))
        rejected(run(record("D1"), record("x".repeat(101), ack = "A")))
    }

    @Test
    fun `INV-AC-28459 request text outside its bounds is rejected`() {
        rejected(run(record("D1"), priorSolution = ""))
        rejected(run(record("D1"), priorSolution = "x".repeat(129)))
        rejected(run(record("D1"), entityRef = ""))
        rejected(run(record("D1"), entityRef = "x".repeat(41)))
    }

    @Test
    fun `INV-AC-28459 a year outside 1 to 9999 is rejected before any store call`() {
        val date = record("D1").transactionDate

        listOf(10_000, 0, -1).forEach { year ->
            rejected(run(record("D0"), record("D1").copy(transactionDate = date.withYear(year))))
        }
    }

    @Test
    fun `INV-AC-28459 every bound itself is accepted, counted in code points`() {
        val astral = "😀"
        val atBounds = Array(500) { record("D$it") }
        atBounds[0] = record(astral.repeat(100), "0001-01-01", ack = astral.repeat(100))
        atBounds[1] = record("x", "9999-12-31", ack = "y")

        val result =
            run(*atBounds, entityRef = astral.repeat(40), priorSolution = astral.repeat(128))

        result.shouldBeInstanceOf<ImportResult.Imported>().importedCount shouldBe 500
        store.rows.take(2).map { it.second.periodRef } shouldBe listOf("0001-Q1", "9999-Q4")
    }

    private fun rejected(result: ImportResult) {
        result.shouldBeInstanceOf<ImportResult.Rejected>()
        nothingTouched()
    }

    private fun nothingTouched() {
        store.calls shouldBe emptyList()
        events shouldBe emptyList()
    }
}
