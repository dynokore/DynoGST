package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqReplayReader
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionStore
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.tenancykernel.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-26475: the replay reader composition and the profileExtras bean carry the apportionmentMode entries, over
 * fake stores; values are compared by whole-value equality.
 */
class ApportionmentModeReplayTest {
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

    private fun day(text: String) = LocalDate.parse(text)

    private val store =
        FakeStore(
            mapOf(
                ("E1" to ElectionKind.APPORTIONMENT_MODE) to
                    listOf(record("CLAIMABLE_PORTION", "2026-07-01"), record("ENTIRE_INVOICE", "2026-08-01")),
                ("E1" to ElectionKind.PURCHASE_DATE_BASIS) to
                    listOf(record("INVOICE_DATE", "2026-01-01"), record("POSTING_DATE", "2026-07-01")),
            ),
        )

    private val mode =
        listOf(
            ProfileEntry("apportionmentMode", "CLAIMABLE_PORTION", day("2026-07-01")),
            ProfileEntry("apportionmentMode", "ENTIRE_INVOICE", day("2026-08-01")),
        )

    /** A stub of a port that no entry of E1 reads: every list is empty, every nullable is null. */
    private inline fun <reified T : Any> empty(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.returnType) {
                List::class.java -> emptyList<Any>()
                else -> null
            }
        } as T

    private fun tenants(): TenantContext {
        val tenant = UUID.randomUUID() // TenantId is an inline class over UUID: the proxy returns the raw value
        return Proxy.newProxyInstance(
            TenantContext::class.java.classLoader,
            arrayOf(TenantContext::class.java),
        ) { _, method, _ -> if (method.name.startsWith("current-")) tenant else null } as TenantContext
    }

    @Test
    fun `INV-AC-26475 case 3 the replay reader composition for E1 carries both families`() {
        val reader = ReplaySourcesConfiguration().replayReader(DSL.using(SQLDialect.POSTGRES), tenants(), store)
        // the function the bean passes: the reader keeps it private and reads it only with a database
        val field = JooqReplayReader::class.java.getDeclaredField("extraEntries").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val entries = (field.get(reader) as (String) -> List<ProfileEntry>)("E1")
        assertThat(entries).isEqualTo(
            listOf(
                ProfileEntry("purchaseDateBasis", "INVOICE_DATE", day("2026-01-01")),
                ProfileEntry("purchaseDateBasis", "POSTING_DATE", day("2026-07-01")),
            ) + mode,
        )
    }

    @Test
    fun `INV-AC-26475 case 4 the profileExtras bean read for E1 carries the same two apportionmentMode items`() {
        val extras =
            ElectionUseCases().profileExtras(
                store,
                empty<PolicyHistory>(),
                tenants(),
                empty<ExemptPurchaseElectionStore>(),
                empty<MigrationCutoverStore>(),
            )
        assertThat(extras.entries("E1").filter { it.attribute == "apportionmentMode" }).isEqualTo(mode)
    }
}
