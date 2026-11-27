package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** INV-AC-357, INV-AC-9601 (dry-run listing): the duplicate, target and rejection rules of INV-Q-17289. */
class ImportAnalysisTest {
    private fun row(
        ref: String?,
        uen: String?,
        name: String? = "N",
    ) = ImportRow(ref, uen, name, "1 Road", "123456")

    private fun heldOf(vararg entries: Pair<String, String>) =
        object : HeldCounterparties {
            private val byRef =
                entries.associate { (ref, uen) ->
                    ref to
                        CounterpartyVersion(
                            PartyRef(ref),
                            1,
                            java.time.Instant.EPOCH,
                            CounterpartyContent(
                                listOf(
                                    Identifier(
                                        IdentifierScheme.UEN,
                                        uen,
                                        Provenance.declared(
                                            ProvidedBy.INTERNAL,
                                            EvidenceRef("e"),
                                            java.time.Instant.EPOCH,
                                        ),
                                    ),
                                ),
                                "N",
                                PostalAddress("1 Road", null, "123456"),
                                null,
                                emptyList(),
                                ProvidedBy.INTERNAL,
                            ),
                        )
                }

            override fun byUen(uen: String) = byRef.values.firstOrNull { it.content.uenOrNull() == uen }

            override fun current(party: PartyRef) = byRef[party.value]
        }

    @Test
    fun `INV-AC-357 two rows with one unheld UEN are both listed and neither is written`() {
        val rows = listOf(row("A", "53012345X"), row("B", "53012346X"), row("C", "201912345A"), row("D", "201912345A"))
        val result = analyseImport(rows, heldOf())
        assertEquals(listOf(ImportDuplicate("201912345A", listOf(3, 4), null)), result.duplicates)
        assertEquals(listOf(1, 2), result.targets.map { it.row })
        assertEquals(2, result.validCount)
    }

    @Test
    fun `INV-AC-357 a row whose UEN another counterparty holds is a duplicate with the existing reference`() {
        val result = analyseImport(listOf(row("CTP-1", "53012345X")), heldOf("CTP-2" to "53012345X"))
        assertEquals(listOf(ImportDuplicate("53012345X", listOf(1), "CTP-2")), result.duplicates)
        assertEquals(0, result.validCount)
    }

    @Test
    fun `INV-AC-357 a row without a reference is written to the holder and an unheld one is MDM-010`() {
        val held = heldOf("CTP-2" to "53012345X")
        val result = analyseImport(listOf(row(null, "53012345X", "New name"), row(null, "53012346X")), held)
        assertEquals(listOf("CTP-2"), result.targets.map { it.party.value })
        assertEquals(1, result.updateCount)
        assertEquals(
            listOf(
                RejectedImportRow(2, "MDM-010", "no counterparty reference and no held UEN match", "/counterpartyRef"),
            ),
            result.rejected,
        )
    }

    @Test
    fun `INV-AC-357 absent and invalid UENs and an incomplete address are rejected with their codes`() {
        val rows =
            listOf(
                row("A", null),
                row("B", "5301234X"),
                ImportRow("C", "53012345X", "N", null, "123456"),
                row("D", "NA"),
            )
        val result = analyseImport(rows, heldOf())
        assertEquals(listOf("MDM-002", "MDM-003", "MDM-006"), result.rejected.map { it.code })
        assertEquals(listOf(4), result.targets.map { it.row })
    }

    private val d1 = LocalDate.parse("2026-01-01")
    private val d2 = LocalDate.parse("2026-12-31")
    private val mes = SchemeInput("MES", d1, d2, "e1")

    private fun status(item: SchemeInput) =
        SchemeStatus(
            item.scheme,
            item.effectiveFrom,
            item.effectiveTo,
            Provenance.declared(ProvidedBy.INTERNAL, EvidenceRef(checkNotNull(item.evidenceRef)), Instant.EPOCH),
        )

    /** One row, equal to the held record `CTP-1` in every other field, analysed alone against [held] items. */
    private fun analyseSchemes(
        supplied: List<SchemeInput>?,
        vararg held: SchemeInput,
    ): ImportAnalysis {
        val base = heldOf("CTP-1" to "53012345X")
        val version = checkNotNull(base.current(PartyRef("CTP-1")))
        val withItems = version.copy(content = version.content.copy(schemes = held.map(::status)))
        val records =
            object : HeldCounterparties by base {
                override fun current(party: PartyRef) = withItems.takeIf { it.partyRef == party }
            }
        return analyseImport(listOf(row("CTP-1", "53012345X").copy(approvedSchemes = supplied)), records)
    }

    private fun assertUnchanged(result: ImportAnalysis) {
        assertEquals(listOf(0, 0, 1), listOf(result.createCount, result.updateCount, result.unchangedCount))
        assertEquals(emptyList<ImportChange>(), result.changes)
    }

    private fun assertSchemeChange(
        result: ImportAnalysis,
        newValue: String,
        oldValue: String?,
    ) {
        assertEquals(listOf(0, 1, 0), listOf(result.createCount, result.updateCount, result.unchangedCount))
        assertEquals(
            listOf(ImportChange("53012345X", "approvedSchemes", newValue, "CTP-1", oldValue)),
            result.changes,
        )
    }

    @Test
    fun `INV-AC-29508 an absent list is no change`() {
        assertUnchanged(analyseSchemes(null, mes))
    }

    @Test
    fun `INV-AC-29508 the same item without evidenceRef is no change`() {
        assertUnchanged(analyseSchemes(listOf(mes.copy(evidenceRef = null)), mes))
    }

    @Test
    fun `INV-AC-29508 the same item with evidenceRef e1 is no change`() {
        assertUnchanged(analyseSchemes(listOf(mes), mes))
    }

    @Test
    fun `INV-AC-29508 a reordered equal list is no change`() {
        val second = SchemeInput("IGDS", d1, null, "e9")
        assertUnchanged(analyseSchemes(listOf(second, mes), mes, second))
    }

    @Test
    fun `INV-AC-29508 another effectiveTo is one approvedSchemes change`() {
        assertSchemeChange(
            analyseSchemes(listOf(mes.copy(effectiveTo = LocalDate.parse("2027-06-30"), evidenceRef = null)), mes),
            "MES:2026-01-01/2027-06-30",
            "MES:2026-01-01/2026-12-31",
        )
    }

    @Test
    fun `INV-AC-29508 another evidenceRef e2 is one approvedSchemes change`() {
        assertSchemeChange(
            analyseSchemes(listOf(mes.copy(evidenceRef = "e2")), mes),
            "MES:2026-01-01/2026-12-31",
            "MES:2026-01-01/2026-12-31",
        )
    }

    @Test
    fun `INV-AC-29508 evidence is compared with the matching item and not with another held item`() {
        val second = SchemeInput("IGDS", d1, d2, "e2")
        assertSchemeChange(
            analyseSchemes(listOf(mes.copy(evidenceRef = "e2"), second), mes, second),
            "IGDS:2026-01-01/2026-12-31;MES:2026-01-01/2026-12-31",
            "IGDS:2026-01-01/2026-12-31;MES:2026-01-01/2026-12-31",
        )
    }

    @Test
    fun `INV-AC-29508 an added item is one change sorted by effectiveFrom, scheme, effectiveTo`() {
        val later = SchemeInput("AAA", LocalDate.parse("2026-02-01"), null, null)
        val shorter = SchemeInput("MES", d1, LocalDate.parse("2026-06-30"), null)
        assertSchemeChange(
            analyseSchemes(listOf(later, mes, shorter, SchemeInput("ZZZ", d1, null, null)), mes),
            "MES:2026-01-01/2026-06-30;MES:2026-01-01/2026-12-31;ZZZ:2026-01-01/;AAA:2026-02-01/",
            "MES:2026-01-01/2026-12-31",
        )
    }

    @Test
    fun `INV-AC-29508 an open-ended item given first renders after the dated one, supplied and held`() {
        val open = mes.copy(effectiveTo = null)
        val both = "MES:2026-01-01/2026-12-31;MES:2026-01-01/"
        assertSchemeChange(analyseSchemes(listOf(open, mes), mes), both, "MES:2026-01-01/2026-12-31")
        assertSchemeChange(analyseSchemes(emptyList(), open, mes), "", both)
    }

    @Test
    fun `INV-AC-29508 an empty list is one change with an empty newValue`() {
        assertSchemeChange(analyseSchemes(emptyList(), mes), "", "MES:2026-01-01/2026-12-31")
    }

    @Test
    fun `INV-AC-29508 a counterparty without items has no oldValue, and an empty list does not change it`() {
        assertSchemeChange(analyseSchemes(listOf(mes)), "MES:2026-01-01/2026-12-31", null)
        assertUnchanged(analyseSchemes(emptyList()))
    }

    @Test
    fun `INV-AC-29508 a held rendering over 500 characters is cut to 500 ending in three dots`() {
        val many = (10..29).map { SchemeInput("SCHEME-$it", d1, d2, "e1") }
        val full = many.joinToString(";") { "${it.scheme}:2026-01-01/2026-12-31" }
        val change = analyseSchemes(emptyList(), *many.toTypedArray()).changes.single()
        assertEquals(full.take(497) + "...", change.oldValue)
        assertEquals(500, change.oldValue?.length)
    }

    @Test
    fun `INV-AC-29508 a rendering of exactly 500 characters is not cut`() {
        // 15 items of 31 characters, one of 20 and 15 separators: 500.
        val many = (10..24).map { SchemeInput("SCHEME-$it", d1, d2, "e1") } + SchemeInput("OPEN-END", d1, null, "e1")
        val change = analyseSchemes(emptyList(), *many.toTypedArray()).changes.single()
        assertEquals(500, change.oldValue?.length)
        assertEquals(false, change.oldValue?.endsWith("..."))
    }

    @Test
    fun `INV-AC-29508 a new counterparty with items is a create that lists the change`() {
        val result = analyseImport(listOf(row("CTP-9", "53012346X").copy(approvedSchemes = listOf(mes))), heldOf())
        assertEquals(listOf(1, 0, 0), listOf(result.createCount, result.updateCount, result.unchangedCount))
        assertEquals(
            listOf(ImportChange("53012346X", "approvedSchemes", "MES:2026-01-01/2026-12-31", "CTP-9", null)),
            result.changes.filter { it.field == "approvedSchemes" },
        )
    }
}
