package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionRelease
import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.AcceptanceEntry
import com.dynokore.platform.integrationcanon.ReleaseOrder
import com.dynokore.platform.integrationcanon.SpecVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class SpecificationReleaseSelectorTest {
    private val pint = SpecificationFamily.PINT_SG
    private val bis = SpecificationFamily.SG_PEPPOL_BIS
    private val synthA = SpecVersion("SYNTH-A")
    private val synthB = SpecVersion("SYNTH-B")
    private val order = ReleaseOrder.of(listOf(synthA, synthB))
    private val entryA = AcceptanceEntry(synthA, LocalDate.parse("2000-01-01"), LocalDate.parse("2000-06-30"))
    private val entryB = AcceptanceEntry(synthB, LocalDate.parse("2000-03-01"), null)
    private val full = AcceptanceCalendar.of(order, listOf(entryA, entryB))
    private val onlyA = AcceptanceCalendar.of(order, listOf(entryA))

    private fun date(text: String) = LocalDate.parse(text)

    private fun selector(
        calendar: AcceptanceCalendar?,
        today: String,
        family: SpecificationFamily = pint,
    ) = SpecificationReleaseSelector({ if (it == family) calendar else null }, { date(today) })

    private fun refused(
        reason: ReleaseRefusalReason,
        family: SpecificationFamily,
        transactionDate: String?,
        today: String,
    ) = ReleaseSelection.Refused(
        NoAcceptedReleaseFacts(reason, family.name, transactionDate?.let(::date), date(today), null, emptyList()),
    )

    @Test
    fun `INV-AC-26559 case 1 selects SYNTH-B with the family given`() {
        selector(full, "2000-07-15").select(pint, date("2000-02-01")) shouldBe
            ReleaseSelection.Selected(SubmissionRelease(pint, synthB))
    }

    @Test
    fun `INV-AC-26559 case 2 selects SYNTH-A the oldest accepted not the newest`() {
        selector(full, "2000-05-01").select(pint, date("2000-04-01")) shouldBe
            ReleaseSelection.Selected(SubmissionRelease(pint, synthA))
    }

    @Test
    fun `INV-AC-26559 case 3 refuses when no release is accepted on the transaction date`() {
        selector(full, "2000-07-15").select(pint, date("1999-12-31")) shouldBe
            refused(ReleaseRefusalReason.NO_RELEASE_ON_TRANSACTION_DATE, pint, "1999-12-31", "2000-07-15")
    }

    @Test
    fun `INV-AC-26559 case 4 refuses without a transaction date`() {
        selector(full, "2000-07-15").select(pint, null) shouldBe
            refused(ReleaseRefusalReason.NO_TRANSACTION_DATE, pint, null, "2000-07-15")
    }

    @Test
    fun `INV-AC-26559 case 5 refuses without a calendar for the family`() {
        selector(null, "2000-07-15").select(pint, date("2000-02-01")) shouldBe
            refused(ReleaseRefusalReason.NO_CALENDAR, pint, "2000-02-01", "2000-07-15")
    }

    @Test
    fun `INV-AC-26559 case 6 refuses with the facts when nothing accepted today is old enough`() {
        selector(onlyA, "2000-07-15").select(pint, date("2000-02-01")) shouldBe
            refused(ReleaseRefusalReason.NO_ACCEPTED_RELEASE, pint, "2000-02-01", "2000-07-15").let {
                ReleaseSelection.Refused(it.facts.copy(notOlderThan = "SYNTH-A", acceptedOnDate = emptyList()))
            }
    }

    @Test
    fun `INV-AC-26559 case 7 selects for the family asked`() {
        selector(full, "2000-07-15", bis).select(bis, date("2000-02-01")) shouldBe
            ReleaseSelection.Selected(SubmissionRelease(bis, synthB))
    }

    @Test
    fun `INV-AC-26559 case 8 takes the build date from the port once per select`() {
        var today = date("2000-05-01")
        var calls = 0
        val selector =
            SpecificationReleaseSelector({ full }, {
                calls++
                today
            })
        val tx = date("2000-02-01")
        selector.select(pint, tx) shouldBe ReleaseSelection.Selected(SubmissionRelease(pint, synthA))
        calls shouldBe 1
        today = date("2000-07-15")
        selector.select(pint, tx) shouldBe ReleaseSelection.Selected(SubmissionRelease(pint, synthB))
        calls shouldBe 2
    }

    @Test
    fun `INV-AC-26559 case 9 keeps the refusal code and facts on the rejected facts`() {
        val facts = refused(ReleaseRefusalReason.NO_CALENDAR, pint, "2000-02-01", "2000-07-15").facts
        val rejected = PayloadRejectedFacts("E", UUID(0, 0), 1, ReleaseRefusal.CODE, null, releaseRefusal = facts)
        rejected.code shouldBe "PAY-020"
        rejected.releaseRefusal shouldBe facts
        ReleaseRefusal.CODE shouldBe "PAY-020"
    }

    @Test
    fun `INV-AC-26559 case 10 leaves regeneration null when not given`() {
        PayloadReadyFacts("E", UUID(0, 0), 1, "T", "S", ByteArray(0), "H", null).regeneration shouldBe null
    }
}
