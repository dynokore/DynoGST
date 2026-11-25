package com.dynokore.gst.payload.adapters

import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.payload.adapters.persistence.GstCodeCatalogue
import com.dynokore.gst.payload.adapters.persistence.GstCodeCatalogue.CatalogueEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The catalogue adapter over the pinned gst-catalogue artefact (INV-Q-17169 A). */
class GstCodeCatalogueTest {
    private val catalogue = GstCodeCatalogue()

    @Test
    fun `PAY-011 is overridable`() {
        catalogue.isOverridable("PAY-011") shouldBe true
    }

    @Test
    fun `PAY-001 is not overridable`() {
        catalogue.isOverridable("PAY-001") shouldBe false
    }

    @Test
    fun `PAY-008 is not overridable`() {
        catalogue.isOverridable("PAY-008") shouldBe false
    }

    @Test
    fun `pay-011 in lower case is not overridable`() {
        catalogue.isOverridable("pay-011") shouldBe false
    }

    @Test
    fun `PAY-0111 is not overridable`() {
        catalogue.isOverridable("PAY-0111") shouldBe false
    }

    @Test
    fun `PAY-011 with a trailing space is not overridable`() {
        catalogue.isOverridable("PAY-011 ") shouldBe false
    }

    @Test
    fun `an empty code is not overridable`() {
        catalogue.isOverridable("") shouldBe false
    }

    @Test
    fun `an entry with a null disposition is not overridable`() {
        GstCodeCatalogue { listOf(CatalogueEntry("X-1", null)) }.isOverridable("X-1") shouldBe false
    }

    @Test
    fun `MANUAL and TERMINAL entries are not overridable and an OVERRIDABLE entry is`() {
        val c =
            GstCodeCatalogue {
                listOf(
                    CatalogueEntry("X-1", Disposition.MANUAL),
                    CatalogueEntry("X-2", Disposition.TERMINAL),
                    CatalogueEntry("X-3", Disposition.OVERRIDABLE),
                )
            }
        listOf("X-1", "X-2", "X-3").map { c.isOverridable(it) } shouldBe listOf(false, false, true)
    }

    @Test
    fun `a code with no entry is not overridable`() {
        GstCodeCatalogue { emptyList() }.isOverridable("PAY-011") shouldBe false
    }

    @Test
    fun `INV-AC-26561 the catalogue holds exactly one PAY-020 entry`() {
        ErrorCode.entries.count { it.code == "PAY-020" } shouldBe 1
    }

    @Test
    fun `INV-AC-26561 PAY-020 has the disposition MANUAL`() {
        ErrorCode.entries.single { it.code == "PAY-020" }.disposition shouldBe Disposition.MANUAL
    }

    @Test
    fun `INV-AC-26561 PAY-020 is not overridable`() {
        catalogue.isOverridable("PAY-020") shouldBe false
    }

    @Test
    fun `INV-AC-26561 look-alike codes find no entry`() {
        val counts = listOf("PAY-0200", "pay-020", "PAY-020 ").map { c -> ErrorCode.entries.count { it.code == c } }
        counts shouldBe listOf(0, 0, 0)
    }
}
