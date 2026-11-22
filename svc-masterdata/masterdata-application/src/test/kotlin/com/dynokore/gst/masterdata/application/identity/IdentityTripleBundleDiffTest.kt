package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

private fun version(
    entity: String = "E-001",
    constitution: String = "PTE_LTD",
    gstn: String = "201912345A",
    from: String = "2026-01-01",
) = TripleVersion(
    EntityRef(entity),
    Constitution(constitution),
    IdentityTriple("0195:SG201912345A", "SUP-1", gstn),
    LocalDate.parse(from),
)

private fun diff(
    part: List<TripleVersion>,
    active: List<TripleVersion>,
): List<IdentityTripleChange> =
    (IdentityTripleBundleDiff.diff(part, active) as IdentityTripleBundleDiffResult.Diff).changes

class IdentityTripleBundleDiffTest {
    @Test
    fun `INV-AC-26952 one differing supplierGstn gives exactly one CHANGED entry`() {
        val changes = diff(listOf(version(gstn = "201912399B")), listOf(version()))
        assertEquals(
            listOf(
                IdentityTripleChange(
                    "identity-triple/E-001/PTE_LTD/supplierGstn",
                    IdentityTripleChangeKind.CHANGED,
                    "201912345A",
                    "201912399B",
                ),
            ),
            changes,
        )
    }

    @Test
    fun `INV-AC-26952 a triple only in the part is ADDED and one only in the active set is REMOVED`() {
        val changes =
            diff(
                listOf(version(entity = "E-002")),
                listOf(version(entity = "E-003", constitution = "SOLE_PROP")),
            )
        assertEquals(
            listOf(
                IdentityTripleChange("identity-triple/E-002/PTE_LTD", IdentityTripleChangeKind.ADDED),
                IdentityTripleChange("identity-triple/E-003/SOLE_PROP", IdentityTripleChangeKind.REMOVED),
            ),
            changes,
        )
    }

    @Test
    fun `INV-AC-26952 equal inputs give an empty result and each differing property is listed alone`() {
        assertTrue(diff(listOf(version()), listOf(version())).isEmpty())
        assertTrue(diff(emptyList(), emptyList()).isEmpty())
        val moved = diff(listOf(version(from = "2026-02-01")), listOf(version()))
        assertEquals(
            listOf(
                IdentityTripleChange(
                    "identity-triple/E-001/PTE_LTD/effectiveFrom",
                    IdentityTripleChangeKind.CHANGED,
                    "2026-01-01",
                    "2026-02-01",
                ),
            ),
            moved,
        )
    }

    @Test
    fun `INV-AC-26952 two triples of one entity and constitution in a part are refused with a typed failure`() {
        val result =
            IdentityTripleBundleDiff.diff(listOf(version(), version(gstn = "201912399B")), listOf(version()))
        assertEquals(IdentityTripleBundleDiffResult.AmbiguousTriples(DiffSide.PART, "E-001", "PTE_LTD"), result)
        val active =
            IdentityTripleBundleDiff.diff(listOf(version()), listOf(version(), version(from = "2026-03-01")))
        assertEquals(IdentityTripleBundleDiffResult.AmbiguousTriples(DiffSide.ACTIVE, "E-001", "PTE_LTD"), active)
    }

    @Test
    fun `INV-AC-26952 the function takes no store`() {
        val methods = IdentityTripleBundleDiff::class.java.declaredMethods.filter { it.name == "diff" }
        assertEquals(1, methods.size)
        assertEquals(listOf(List::class.java, List::class.java), methods.single().parameterTypes.toList())
    }
}
