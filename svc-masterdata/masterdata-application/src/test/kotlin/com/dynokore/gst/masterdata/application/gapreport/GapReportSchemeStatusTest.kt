package com.dynokore.gst.masterdata.application.gapreport

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.gst.masterdata.application.rate.directRunner
import com.dynokore.gst.masterdata.application.registry.LookupEvidenceLog
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.gst.masterdata.application.registry.RegisterAccess
import com.dynokore.gst.masterdata.application.registry.RegisterCache
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.gst.masterdata.application.registry.RegisterPorts
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestions
import com.dynokore.gst.masterdata.application.registry.RegistryPolicies
import com.dynokore.gst.masterdata.application.request.DirectTx
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.GapClass
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeStatus
import com.dynokore.gst.masterdata.domain.counterparty.UenFormat
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.counterparty.after
import com.dynokore.gst.masterdata.domain.counterparty.gapRows
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-REQ-316 class 6 (INV-REQ-2445): SCHEME_STATUS_MISSING from the replica port, a pure read of own tables. */
class GapReportSchemeStatusTest {
    private val entity = "ENT-1"
    private val now = Instant.parse("2026-11-06T01:00:00Z")
    private val runDate = LocalDate.of(2026, 11, 6)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val provenance =
        null
            .after(
                CounterpartyChange(
                    listOf(IdentifierInput(IdentifierScheme.UEN, "201912345A")),
                    "Acme",
                    null,
                    null,
                    null,
                ),
                "EV",
                WriteOrigin.OPERATOR,
                now,
            ).identifiers
            .single()
            .provenance

    private fun status(
        from: LocalDate,
        to: LocalDate?,
    ) = SchemeStatus("PEPPOL", from, to, provenance)

    private fun party(
        ref: String,
        uen: String,
        vararg schemes: SchemeStatus,
        address: PostalAddress? = PostalAddress("1 Raffles Place", null, "048616"),
    ): CounterpartyVersion {
        val v =
            CounterpartyVersion(
                PartyRef(ref),
                1,
                now,
                null.after(
                    CounterpartyChange(listOf(IdentifierInput(IdentifierScheme.UEN, uen)), "Acme", address, null, null),
                    "EV",
                    WriteOrigin.OPERATOR,
                    now,
                ),
            )
        return v.copy(content = v.content.copy(schemes = schemes.toList()))
    }

    private class Parties(
        private val all: List<CounterpartyVersion>,
    ) : CounterpartyRepository by proxy() {
        override fun allCurrent() = all
    }

    private class Store : GapReportStore {
        val records = mutableMapOf<String, GapReportRecord>()
        val failed = mutableListOf<String>()

        override fun insertRunning(
            gapReportId: String,
            entity: String,
            actor: String,
        ) = GapReportRecord(gapReportId, entity, actor, RecordState.RUNNING, Instant.EPOCH, null, null, null)
            .also { records[gapReportId] = it }

        override fun finish(
            gapReportId: String,
            result: GapReportResult,
        ) {
            records[gapReportId] =
                records.getValue(gapReportId).copy(state = RecordState.SUCCEEDED, result = result)
        }

        override fun fail(gapReportId: String) {
            failed += gapReportId
            records[gapReportId] = records.getValue(gapReportId).copy(state = RecordState.FAILED)
        }

        override fun find(gapReportId: String) = records[gapReportId]

        override fun failRunningBefore(before: Instant) = 0
    }

    private fun held(
        ref: String,
        entity: String = this.entity,
    ) = HeldDocumentFact(HeldKind.HELD, UUID.randomUUID(), 1, entity, ref, setOf("RUL-011"))

    private fun run(
        parties: List<CounterpartyVersion>,
        port: HeldDocuments,
        store: Store = Store(),
    ): GapReportResult {
        val tx = DirectTx
        val repo = Parties(parties)
        val register =
            LookupGstRegister(
                tx,
                repo,
                RegisterAccess(
                    tx,
                    RegisterPorts(
                        proxy<RegisterCache>(),
                        RegisterClient { error("no register lookup expected: no counterparty holds a GSTN") },
                        proxy<RegistryPolicies>(),
                        LookupEvidenceLog { _, _, _ -> error("no evidence expected") },
                    ),
                    clock,
                ),
                proxy<RegisterSuggestions>(),
                RecordCounterparty(tx, repo, clock, proxy<MasterDataResolvedPublisher>()),
                clock,
            )
        val out = RunGapReport(tx, repo, store, register, port, clock, directRunner()).handle(entity, "IDN-0001")
        return checkNotNull(out.record.result)
    }

    private fun portWith(vararg facts: HeldDocumentFact): HeldDocuments =
        com.dynokore.gst.masterdata.application.request
            .FakeHeld()
            .also { h -> facts.forEach { h.apply(it) } }

    private val a = party("CP-A", "201912345A")
    private val b = party("CP-B", "201912346B")
    private val c = party("CP-C", "201912347C", status(runDate.minusDays(10), null))

    private fun pairs(r: GapReportResult) = r.rows.map { it.counterpartyRef to it.findingClass }

    @Test
    fun `INV-AC-26221 A without scheme status and blocked is listed with no error code`() {
        val r = run(listOf(a, b, c), portWith(held("CP-A"), held("CP-C")))
        val rows = r.rows.filter { it.findingClass == GapClass.SCHEME_STATUS_MISSING }
        assertEquals(listOf(GapFinding("CP-A", GapClass.SCHEME_STATUS_MISSING, null)), rows)
        assertEquals(null, rows.single().errorCode)
    }

    @Test
    fun `INV-AC-26221 B without scheme status and not blocked has no such row`() {
        val r = run(listOf(a, b, c), portWith(held("CP-A")))
        assertTrue(r.rows.none { it.counterpartyRef == "CP-B" })
    }

    @Test
    fun `INV-AC-26221 C with scheme status in force and blocked has no such row`() {
        val r = run(listOf(a, b, c), portWith(held("CP-C")))
        assertTrue(r.rows.none { it.findingClass == GapClass.SCHEME_STATUS_MISSING })
    }

    @Test
    fun `INV-AC-26221 a scheme status that ended before the run date counts as not in force`() {
        val ended = party("CP-D", "201912348D", status(runDate.minusDays(30), runDate.minusDays(1)))
        val r = run(listOf(ended), portWith(held("CP-D")))
        assertEquals(listOf("CP-D" to GapClass.SCHEME_STATUS_MISSING), pairs(r))
    }

    @Test
    fun `INV-AC-26221 a blocked reference that is no current counterparty returns no row`() {
        val r = run(listOf(a), portWith(held("CP-GHOST")))
        assertTrue(r.rows.isEmpty())
    }

    @Test
    fun `INV-AC-26221 the blocked set is read for the entity of the request only`() {
        val r = run(listOf(a), portWith(held("CP-A", entity = "ENT-OTHER")))
        assertTrue(r.rows.isEmpty())
    }

    @Test
    fun `INV-AC-26221 the class is named SCHEME_STATUS_MISSING and the row has no RUL-011 code`() {
        val row = run(listOf(a), portWith(held("CP-A"))).rows.single()
        assertEquals("SCHEME_STATUS_MISSING", row.findingClass.name)
        assertEquals(null, row.errorCode)
    }

    @Test
    fun `INV-AC-26221 the rows of the five existing classes equal those of main`() {
        val bad = party("CP-E", "BAD", address = PostalAddress(null, null, null))
        val dup1 = party("CP-F", "201912349F")
        val dup2 = party("CP-G", "201912349F")
        val all = listOf(a, bad, dup1, dup2, c)
        val expected =
            gapRows(all) { UenFormat.accepts(it) }
                .map { it.party.value to it.gapClass }
                .sortedWith(compareBy({ it.first }, { it.second.ordinal }))
        val withBlocked = run(all, portWith(held("CP-A"), held("CP-E")))
        assertEquals(expected, pairs(withBlocked).filter { it.second != GapClass.SCHEME_STATUS_MISSING })
        assertEquals(
            listOf("CP-A", "CP-E"),
            withBlocked.rows.filter { it.findingClass == GapClass.SCHEME_STATUS_MISSING }.map { it.counterpartyRef },
        )
        // a party already listed under another class is listed again under the new one
        assertTrue("CP-E" to GapClass.ADDRESS_INCOMPLETE in pairs(withBlocked))
        assertEquals(expected, pairs(run(all, portWith())))
    }

    @Test
    fun `INV-AC-26221 when the port throws the run is recorded FAILED and no result is stored`() {
        val store = Store()
        val failing =
            object : HeldDocuments by proxy() {
                override fun rul011Blocked(entity: String): Set<PartyRef> = error("replica unavailable")
            }
        assertThrows<IllegalStateException> { run(listOf(a), failing, store) }
        assertEquals(1, store.failed.size)
        val record = store.records.getValue(store.failed.single())
        assertEquals(RecordState.FAILED, record.state)
        assertEquals(null, record.result)
    }

    companion object {
        /** A port whose every method throws: the test uses only the methods it overrides. */
        inline fun <reified T : Any> proxy(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
                throw UnsupportedOperationException("unexpected call of ${m.name}")
            } as T
    }
}
