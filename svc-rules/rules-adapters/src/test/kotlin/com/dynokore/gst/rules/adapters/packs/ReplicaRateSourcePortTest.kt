package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.RateSourceAcceptance
import com.dynokore.gst.rules.application.RateSourceAcceptanceStore
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The rate-source block of one tenant over a fake replica and a fake scope pack (INV-REQ-504, INV-Q-16533 A). */
class ReplicaRateSourcePortTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2027, 1, 1)
    private val replica = StructureReplica(clock).also { it.onSnapshotComplete(1) }

    private fun source(
        id: String,
        sourceClass: String,
    ) = """{"sourceId":"$id","name":"n","bases":["b"],"sourceClass":"$sourceClass","sourceReference":"r"}"""

    private fun pack(vararg sources: Pair<String, String>) =
        """{"exchangeRateSources":[${sources.joinToString(",") { source(it.first, it.second) }}]}"""

    private val standard = pack("RS-I" to "INFERRED", "RS-P" to "PRIMARY", "RS-S" to "SECONDARY", "RS-J" to "INFERRED")

    private inner class Election(
        val ref: String,
        val value: String?,
        val on: LocalDate = day,
        val owner: TenantId = tenant,
        val retired: Boolean = false,
        val attribute: String = ReplicaRateSourcePort.ATTRIBUTE,
    ) {
        var kind = StructureNodeKind.ENTITY
    }

    private fun elect(
        ref: String,
        value: String?,
    ) = elect(Election(ref, value))

    private fun elect(e: Election) =
        replica.accept(
            EntityProfileChanged(
                e.owner,
                e.ref,
                StructureNode(
                    e.kind,
                    null,
                    e.retired,
                    "E",
                    e.ref,
                    listOfNotNull(e.value?.let { ProfileEntry(e.attribute, it, e.on) }),
                    1,
                ),
            ),
        )

    /** Answers every held row whatever tenant is asked, so the tenant condition of the port itself is under test. */
    private class Acceptances : RateSourceAcceptanceStore {
        val held = mutableListOf<RateSourceAcceptance>()
        val asked = mutableListOf<TenantId>()
        var failing = false

        override fun replace(acceptance: RateSourceAcceptance) {
            held += acceptance
        }

        override fun byTenant(tenant: TenantId): List<RateSourceAcceptance> {
            asked += tenant
            check(!failing) { "the store cannot be read" }
            return held.toList()
        }
    }

    private val acceptances = Acceptances()

    private fun accept(
        sourceId: String,
        expiresOn: LocalDate = day.plusDays(1),
        who: TenantId = tenant,
    ) = acceptances.replace(RateSourceAcceptance(who, sourceId, "seen", expiresOn, "user-c"))

    private fun port(
        contents: String? = standard,
        structure: StructureReplica = replica,
    ) = ReplicaRateSourcePort(structure, acceptances) { _, _ -> contents?.toByteArray() }

    private fun blocking(
        asOf: LocalDate = day,
        who: TenantId = tenant,
    ) = port().blocking(who, asOf)

    @Test
    fun `INV-AC-15992 a tenant with no entity holding the attribute gives the empty set`() {
        elect("E1", null)
        blocking() shouldBe emptyList()
        port().blocking(other, day) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-15992 an entity electing an INFERRED id gives that id`() {
        elect("E1", "RS-I")
        blocking() shouldBe listOf("RS-I")
    }

    @Test
    fun `an id listed PRIMARY or SECONDARY gives the empty set`() {
        elect("E1", "RS-P")
        elect("E2", "RS-S")
        blocking() shouldBe emptyList()
    }

    @Test
    fun `an elected id that is not on the list is not reported`() {
        elect("E1", "RS-X")
        blocking() shouldBe emptyList()
    }

    @Test
    fun `two entities electing the same id give it once`() {
        elect("E1", "RS-I")
        elect("E2", "RS-I")
        elect("E3", "RS-J")
        blocking() shouldBe listOf("RS-I", "RS-J")
    }

    @Test
    fun `an election whose effective date is after asOf is not counted`() {
        elect(Election("E1", "RS-I", on = day.plusDays(1)))
        blocking() shouldBe emptyList()
        blocking(day.plusDays(1)) shouldBe listOf("RS-I")
    }

    @Test
    fun `the entity of another tenant is not counted`() {
        elect(Election("E1", "RS-I", owner = other))
        blocking() shouldBe emptyList()
        blocking(who = other) shouldBe listOf("RS-I")
    }

    @Test
    fun `a retired entity node is not counted`() {
        elect(Election("E1", "RS-I", retired = true))
        blocking() shouldBe emptyList()
    }

    @Test
    fun `a node that is not an entity is not counted`() {
        elect(Election("U1", "RS-I").also { it.kind = StructureNodeKind.BUSINESS_UNIT })
        blocking() shouldBe emptyList()
    }

    @Test
    fun `the attribute name exchangeRateSources is not read`() {
        elect(Election("E1", "RS-I", attribute = "exchangeRateSources"))
        elect(Election("E2", "RS-I", attribute = "ExchangeRateSource"))
        blocking() shouldBe emptyList()
    }

    @Test
    fun `a replica that is not synced gives null, not the empty set`() {
        val cold = StructureReplica(clock)
        val node = StructureNode(StructureNodeKind.ENTITY, null, false, "E", "E1", emptyList(), 1)
        cold.accept(EntityProfileChanged(tenant, "E1", node))
        port(structure = cold).blocking(tenant, day) shouldBe null
        replica.onDisconnected()
        blocking() shouldBe null
    }

    @Test
    fun `no scope pack in force gives null`() {
        elect("E1", "RS-I")
        port(contents = null).blocking(tenant, day) shouldBe null
    }

    @Test
    fun `contents that do not decode give null`() {
        elect("E1", "RS-I")
        listOf(
            "not json",
            "{}",
            """{"exchangeRateSources":[]}""",
            """{"exchangeRateSource":[${source("RS-I", "INFERRED")}]}""",
            pack("RS-I" to "GUESSED"),
            pack("RS-I" to "inferred"),
            """{"exchangeRateSources":[{"sourceClass":"INFERRED"}]}""",
            """{"exchangeRateSources":[${source("RS-I", "INFERRED")},{"sourceId":"RS-X","sourceClass":"X"}]}""",
        ).forEach { port(contents = it).blocking(tenant, day) shouldBe null }
    }

    @Test
    fun `no acceptance is read, a look-alike acceptance row of a mapping-pack entry does not clear the source`() {
        elect("E1", "RS-I")
        // an acceptance of a mapping-pack entry with the same text id: the port reads none, so the source stays
        val accepted = standard.dropLast(1) + ""","acceptances":[{"entryId":"RS-I","validUntil":"2099-01-01"}]}"""
        port(contents = accepted).blocking(tenant, day) shouldBe listOf("RS-I")
    }

    // ---- the acceptance of an INFERRED source (INV-T-24951, INV-AC-24949) ----

    private val twoInferred = pack("S1" to "INFERRED", "S2" to "INFERRED")

    private fun blockingOfS1(): List<String>? {
        elect("E1", "S1")
        return port(contents = twoInferred).blocking(tenant, day)
    }

    @Test
    fun `INV-AC-24949 case 1 with no acceptance the elected source blocks`() {
        blockingOfS1() shouldBe listOf("S1")
        acceptances.asked shouldBe listOf(tenant)
    }

    @Test
    fun `INV-AC-24949 case 2 an acceptance that expires the day after asOf clears the source`() {
        accept("S1", expiresOn = day.plusDays(1))
        blockingOfS1() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24949 case 3 an acceptance that expires on asOf does not clear the source`() {
        accept("S1", expiresOn = day)
        blockingOfS1() shouldBe listOf("S1")
    }

    @Test
    fun `INV-AC-24949 case 4 an acceptance of another tenant is ignored`() {
        accept("S1", who = other)
        blockingOfS1() shouldBe listOf("S1")
    }

    @Test
    fun `INV-AC-24949 case 5 an acceptance of the other source is ignored`() {
        accept("S2")
        blockingOfS1() shouldBe listOf("S1")
    }

    @Test
    fun `INV-AC-24949 an expired acceptance does not clear the source`() {
        accept("S1", expiresOn = day.minusDays(1))
        blockingOfS1() shouldBe listOf("S1")
    }

    @Test
    fun `INV-AC-24949 an acceptance clears only its own source of two elected ones`() {
        accept("S1")
        elect("E2", "S2")
        blockingOfS1() shouldBe listOf("S2")
    }

    @Test
    fun `an acceptance of S10 does not clear S1 and an acceptance of S1 does not clear S10`() {
        val both = pack("S1" to "INFERRED", "S10" to "INFERRED")
        elect("E1", "S1")
        accept("S10")
        port(contents = both).blocking(tenant, day) shouldBe listOf("S1")
        acceptances.held.clear()
        elect("E2", "S10")
        accept("S1")
        port(contents = both).blocking(tenant, day) shouldBe listOf("S10")
    }

    @Test
    fun `an acceptance of a mapping-pack entry with the same text id is not an acceptance of the source`() {
        // rules.mapping_entry_acceptance is not behind this port's store, so the store holds nothing for the id;
        // RateSourceAcceptanceBoundaryTest proves the same over the real table
        elect("E1", "S1")
        port(contents = twoInferred).blocking(tenant, day) shouldBe listOf("S1")
    }

    @Test
    fun `an acceptance store that cannot be read gives null, never the shorter list`() {
        elect("E1", "S1")
        accept("S1")
        acceptances.failing = true
        port(contents = twoInferred).blocking(tenant, day) shouldBe null
        port(contents = twoInferred).blocking(other, day) shouldBe null
    }

    // ---- the inferred ids (InferredRateSourcePort) ----

    @Test
    fun `inferred is exactly the ids the pack lists as INFERRED`() {
        port().inferred(tenant, day) shouldBe setOf("RS-I", "RS-J")
    }

    @Test
    fun `inferred leaves out a source listed with another class`() {
        val mixed = pack("S1" to "INFERRED", "S2" to "PRIMARY", "S3" to "SECONDARY")
        port(contents = mixed).inferred(tenant, day) shouldBe setOf("S1")
    }

    @Test
    fun `inferred is null exactly when blocking is null for the same input`() {
        elect("E1", "RS-I")
        val cold = StructureReplica(clock)
        val inputs =
            listOf(
                port(structure = cold) to true,
                port(contents = null) to true,
                port(contents = "not json") to true,
                port(contents = pack("RS-I" to "GUESSED")) to true,
                port() to false,
                port(contents = pack("RS-P" to "PRIMARY")) to false,
            )
        inputs.forEach { (port, unanswerable) ->
            (port.blocking(tenant, day) == null) shouldBe unanswerable
            (port.inferred(tenant, day) == null) shouldBe unanswerable
        }
        port(contents = pack("RS-P" to "PRIMARY")).inferred(tenant, day) shouldBe emptySet()
    }

    @Test
    fun `inferred does not depend on the elections of any tenant or on an acceptance`() {
        val without = port().inferred(tenant, day)
        elect(Election("E1", "RS-P", owner = other))
        elect("E2", "RS-X")
        accept("RS-I")
        port().inferred(tenant, day) shouldBe without
        port().inferred(other, day) shouldBe without
    }

    @Test
    fun `inferred holds S1 and not the look-alike S10`() {
        val listed = pack("S1" to "INFERRED", "S10" to "PRIMARY")
        val inferred = port(contents = listed).inferred(tenant, day)
        inferred shouldBe setOf("S1")
        (inferred?.contains("S10")) shouldBe false
        port(contents = pack("S1" to "INFERRED")).inferred(tenant, day)?.contains("S10") shouldBe false
    }

    @Test
    fun `decode returns the pairs in pack order and null for a singular member or an empty list`() {
        ScopePackRateSources.decode(standard.toByteArray()) shouldBe
            listOf("RS-I" to "INFERRED", "RS-P" to "PRIMARY", "RS-S" to "SECONDARY", "RS-J" to "INFERRED")
        val singular = """{"exchangeRateSource":[${source("A", "PRIMARY")}]}"""
        ScopePackRateSources.decode(singular.toByteArray()) shouldBe null
        ScopePackRateSources.decode("""{"exchangeRateSources":[]}""".toByteArray()) shouldBe null
        ScopePackRateSources.decode(pack("A" to "TERTIARY").toByteArray()) shouldBe null
        val noId = """{"exchangeRateSources":[{"sourceClass":"PRIMARY"}]}"""
        ScopePackRateSources.decode(noId.toByteArray()) shouldBe null
    }
}
