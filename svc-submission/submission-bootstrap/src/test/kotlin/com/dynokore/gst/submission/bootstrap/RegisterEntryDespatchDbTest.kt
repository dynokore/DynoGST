package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The register entry (`period_ref`, `due_date`) before and after a despatch cycle run (INV-REQ-68). */
class RegisterEntryDespatchDbTest {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    private data class Stored(
        val periodRef: String,
        val dueDate: LocalDate,
    )

    private fun entry(
        doc: String,
        period: String,
        due: LocalDate,
        transactionDate: LocalDate,
    ) = RegisterEntry(
        docUuid = doc,
        entityRef = "E-001",
        periodRef = period,
        dueDate = due,
        inScope = true,
        kind = RowKind.INVOICE,
        facts =
            ListFacts(
                documentNumber = "INV-$doc".take(DOC_NUMBER_LENGTH),
                documentTypeCode = "380",
                direction = Direction.SALE,
                counterpartyName = "Acme Pte Ltd",
                counterpartyUen = "201912345A",
                taxableAmount = BigDecimal("100.00"),
                gstAmount = BigDecimal("9.00"),
                categoryCodes = setOf("SR"),
            ),
        sourceVersion = 1,
        transactionDate = transactionDate,
    )

    private fun storedOf(
        f: OutcomeFixture,
        tenant: UUID,
        doc: String,
    ): Stored =
        f.asTenant(tenant) {
            val r =
                f.dsl
                    .selectFrom(e)
                    .where(e.DOC_UUID.eq(doc))
                    .fetchSingle()
            Stored(checkNotNull(r.periodRef), checkNotNull(r.dueDate))
        }

    @Test
    fun `INV-AC-139 the register entry period and due date are unchanged by a despatch cycle run on 2 Apr 2026`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).run { context ->
            val f = OutcomeFixture(context)
            val register = JooqRegisterEntryStore(f.dsl, f.runner)
            val tenant = UUID.randomUUID()
            val docA = UUID.randomUUID().toString()
            val docB = UUID.randomUUID().toString()
            val march31 = LocalDate.of(2026, 3, 31)
            val q1 = "2026-Q1"
            val q1Due = LocalDate.of(2026, 4, 30)
            val q2 = "2026-Q2"
            val q2Due = LocalDate.of(2026, 7, 31)

            // (1) the arrival entries: A (31 Mar 2026, Jan to Mar 2026) and the look-alike B of another period
            f.queued(tenant, docA)
            f.queued(tenant, docB)
            f.tenants.bind(TenantId(tenant), f.actor) {
                register.upsert(TenantId(tenant), entry(docA, q1, q1Due, march31)) shouldBe UpsertResult.INSERTED
                register.upsert(TenantId(tenant), entry(docB, q2, q2Due, LocalDate.of(2026, 6, 30))) shouldBe
                    UpsertResult.INSERTED
            }

            // (2) the values read through the store's table before the cycle
            val beforeA = storedOf(f, tenant, docA)
            val beforeB = storedOf(f, tenant, docB)
            beforeA shouldBe Stored(q1, q1Due)
            beforeB shouldBe Stored(q2, q2Due)

            // (3) one despatch cycle with the clock at 2 Apr 2026
            f.clock.now = Instant.parse("2026-04-02T09:00:00Z")
            val cycle = f.cycle(FakeTransport(TransportResult.Accepted("AP-1", f.clock.now, "ok".toByteArray())))
            cycle.runOnceItem() shouldBe DespatchedVersion(docA, 1)

            // (4) the same two columns again equal the arrival values
            val afterA = storedOf(f, tenant, docA)
            afterA.periodRef shouldBe beforeA.periodRef
            afterA.dueDate shouldBe beforeA.dueDate

            // (5) the look-alike keeps its own period and due date, different from A's
            val afterB = storedOf(f, tenant, docB)
            afterB.periodRef shouldBe q2
            afterB.dueDate shouldBe q2Due
            (afterB.periodRef == afterA.periodRef) shouldBe false
            (afterB.dueDate == afterA.dueDate) shouldBe false
        }
    }

    private companion object {
        const val DOC_NUMBER_LENGTH = 20
    }
}
