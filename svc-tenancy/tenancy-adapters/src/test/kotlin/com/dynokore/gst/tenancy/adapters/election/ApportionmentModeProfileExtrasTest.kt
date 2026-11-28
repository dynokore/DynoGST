package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.domain.ElectionKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** INV-AC-26475: the apportionmentMode entries of the profile read, over a fake election store. */
class ApportionmentModeProfileExtrasTest {
    private class FakeStore(
        private val histories: Map<Pair<String, ElectionKind>, List<ElectionRecord>>,
    ) : ElectionPolicyStore {
        override fun history(
            entityRef: String,
            kind: ElectionKind,
        ): List<ElectionRecord> = histories[entityRef to kind].orEmpty()

        @Suppress("LongParameterList")
        override fun write(
            entityRef: String,
            groupRef: String,
            kind: ElectionKind,
            value: String,
            basis: String?,
            effectiveFrom: LocalDate,
            actor: String,
            reason: String,
        ): Unit = error("the fake store is read-only")
    }

    private fun record(
        value: String,
        from: String,
    ) = ElectionRecord(value, null, LocalDate.parse(from), "identity:USR-001")

    private val store =
        FakeStore(
            mapOf(
                ("E1" to ElectionKind.APPORTIONMENT_MODE) to
                    listOf(record("CLAIMABLE_PORTION", "2026-07-01"), record("ENTIRE_INVOICE", "2026-08-01")),
                ("E1" to ElectionKind.PURCHASE_DATE_BASIS) to listOf(record("INVOICE_DATE", "2026-01-01")),
            ),
        )

    @Test
    fun `INV-AC-26475 case 1 the history of E1 gives exactly the two apportionmentMode entries in history order`() {
        ApportionmentModeProfileExtras(store).entries("E1") shouldBe
            listOf(
                ProfileEntry("apportionmentMode", "CLAIMABLE_PORTION", LocalDate.parse("2026-07-01")),
                ProfileEntry("apportionmentMode", "ENTIRE_INVOICE", LocalDate.parse("2026-08-01")),
            )
    }

    @Test
    fun `INV-AC-26475 case 2 an entity with no election gives the empty list`() {
        ApportionmentModeProfileExtras(store).entries("E2") shouldBe emptyList()
    }
}
