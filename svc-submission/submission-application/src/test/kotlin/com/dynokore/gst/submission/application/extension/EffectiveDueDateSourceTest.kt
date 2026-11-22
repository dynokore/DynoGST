package com.dynokore.gst.submission.application.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The extension row that applies to a document, as the list row source reads it (INV-AC-20729). */
class EffectiveDueDateSourceTest {
    private val register = LocalDate.of(2026, 4, 30)
    private val doc = "11111111-1111-1111-1111-111111111111"
    private val inRange = LocalDate.of(2026, 3, 20)

    private fun row(
        kind: String,
        ref: String?,
        date: String,
        target: String = "SUBMISSION_DUE_DATE",
    ) = ExtensionInput(target, kind, ref, LocalDate.parse(date))

    private fun source(
        vararg rows: ExtensionInput,
        tx: LocalDate? = inRange,
    ) = EffectiveDueDate.resolveSource(register, doc, tx, rows.toList())

    @Test
    fun `INV-AC-20729 a PERIOD row gives the original and the extended date`() {
        source(row("PERIOD", null, "2026-05-14")) shouldBe
            ExtensionSource(register, LocalDate.of(2026, 5, 14))
    }

    @Test
    fun `INV-AC-20729 a DATE_RANGE row gives the extended date only`() {
        source(row("DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01")) shouldBe
            ExtensionSource(null, LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `INV-AC-20729 the narrowest scope wins as in the resolver`() {
        source(
            row("PERIOD", null, "2026-05-14"),
            row("DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01"),
            row("DOCUMENT_SET", doc, "2026-05-20"),
        ) shouldBe ExtensionSource(register, LocalDate.of(2026, 5, 20))
        source(
            row("PERIOD", null, "2026-05-14"),
            row("DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01"),
        ) shouldBe ExtensionSource(null, LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `INV-AC-20729 no row gives none`() {
        source() shouldBe null
        source(row("DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01"), tx = LocalDate.of(2026, 4, 2)) shouldBe null
    }

    @Test
    fun `INV-AC-20729 a FILING_DUE_DATE row gives none`() {
        source(row("PERIOD", null, "2026-05-31", target = "FILING_DUE_DATE")) shouldBe null
    }
}
