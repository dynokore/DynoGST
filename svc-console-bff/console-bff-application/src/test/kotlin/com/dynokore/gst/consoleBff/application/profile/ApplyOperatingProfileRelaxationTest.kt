package com.dynokore.gst.consoleBff.application.profile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApplyOperatingProfileRelaxationTest {
    private val vp03 = ProfileValue("VP-03", listOf("EXTERNAL_ADVISOR"), "SOLE_OPERATOR_ACK")
    private val vp40 = ProfileValue("VP-40", listOf("SINGLE_ATTESTATION_SOLE_OPERATOR"))
    private val prf1 = ProfileDefinition("PRF-1", 1, ProfileStatus.AVAILABLE, listOf(vp03, vp40))
    private val planned = ProfileDefinition("PRF-3", 1, ProfileStatus.PLANNED, listOf(vp03))

    private class Fixture(
        facts: Map<Triple<String, String, String>, RelaxationFact>,
        pending: List<String> = emptyList(),
        failReader: Boolean = false,
        profiles: List<ProfileDefinition>,
    ) {
        val commands = mutableListOf<ProfileApplyCommand>()
        val reads = mutableListOf<Triple<String, String, String>>()
        val useCase =
            ApplyOperatingProfile(
                { _, _, _, _ -> true },
                { ref, v -> profiles.firstOrNull { it.profileRef == ref && it.version == v } },
                { _, _, _ -> true },
                { _, _, _ -> null },
                {
                    commands += it
                    ProfileApplyResult.Applied(emptyList(), pending)
                },
                { t, e, id ->
                    reads += Triple(t, e, id)
                    if (failReader) error("down")
                    facts[Triple(t, e, id)]
                },
            )

        fun run(
            ids: List<String>,
            mode: ProfileMode = ProfileMode.APPLY,
            ref: String = "PRF-1",
        ) = useCase.execute("t1", "u1", "ENT-1", ApplyOperatingProfileRequest(mode, ProfileScope.TENANT, ref, 1, ids))
    }

    private fun key(id: String) = Triple("t1", "ENT-1", id)

    private fun fixture(
        vararg facts: RelaxationFact,
        pending: List<String> = emptyList(),
        failReader: Boolean = false,
    ) = Fixture(facts.associateBy { key(it.relaxationId) }, pending, failReader, listOf(prf1, planned))

    private val ok3 = RelaxationFact("r3", "VP-03", true)
    private val ok40 = RelaxationFact("r40", "VP-40", true)

    @Test
    fun `INV-AC-26819 R1 complete relaxations reach the applier and its pending list is returned`() {
        val f = fixture(ok3, ok40)
        f.run(listOf("r3", "r40")) shouldBe ApplyOperatingProfileOutcome.Applied("PRF-1", 1, emptyList(), emptyList())
        f.commands.single().completeRelaxations shouldBe setOf("VP-03", "VP-40")
    }

    @Test
    fun `INV-AC-26819 R2 an empty list gives an empty set and the pending list passes through`() {
        val f = fixture(ok3, pending = listOf("VP-03", "VP-40"))
        val expected = ApplyOperatingProfileOutcome.Applied("PRF-1", 1, emptyList(), listOf("VP-03", "VP-40"))
        f.run(emptyList()) shouldBe expected
        f.useCase.execute(
            "t1",
            "u1",
            "ENT-1",
            ApplyOperatingProfileRequest(ProfileMode.APPLY, ProfileScope.TENANT, "PRF-1", 1),
        ) shouldBe expected
        f.commands.map { it.completeRelaxations } shouldBe listOf(emptySet(), emptySet())
        f.reads.size shouldBe 0
    }

    @Test
    fun `INV-AC-26819 R3 an incomplete relaxation is not in the set`() {
        val f = fixture(RelaxationFact("r3", "VP-03", false))
        f.run(listOf("r3"))
        f.commands.single().completeRelaxations shouldBe emptySet()
    }

    @Test
    fun `INV-AC-26819 R4 an unknown relaxation is not in the set`() {
        val f = fixture(ok3)
        f.run(listOf("nope"))
        f.commands.single().completeRelaxations shouldBe emptySet()
    }

    @Test
    fun `INV-AC-26819 R5 a relaxation of another tenant or entity is not in the set`() {
        val f =
            Fixture(
                mapOf(
                    Triple("t2", "ENT-1", "a") to RelaxationFact("a", "VP-03", true),
                    Triple("t1", "ENT-2", "b") to RelaxationFact("b", "VP-40", true),
                ),
                profiles = listOf(prf1),
            )
        f.run(listOf("a", "b"))
        f.commands.single().completeRelaxations shouldBe emptySet()
        f.reads shouldBe listOf(key("a"), key("b"))
    }

    @Test
    fun `INV-AC-26819 R6 the same id twice is read once`() {
        val f = fixture(ok3)
        f.run(listOf("r3", "r3"))
        f.reads shouldBe listOf(key("r3"))
        f.commands.single().completeRelaxations shouldBe setOf("VP-03")
    }

    @Test
    fun `INV-AC-26819 R7 a blank id is refused with nothing read and nothing applied`() {
        listOf("", "   ").forEach { blank ->
            val f = fixture(ok3)
            f.run(listOf("r3", blank)) shouldBe
                ApplyOperatingProfileOutcome.Invalid("relaxationIds holds a blank id")
            f.reads.size shouldBe 0
            f.commands.size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-26819 R8 a failing reader gives Unavailable and nothing is applied`() {
        val f = fixture(ok3, failReader = true)
        f.run(listOf("r3")) shouldBe ApplyOperatingProfileOutcome.Unavailable("port failure: IllegalStateException")
        f.commands.size shouldBe 0
    }

    @Test
    fun `INV-AC-26819 R9 mode PREVIEW reads no relaxation and applies nothing`() {
        val f = fixture(ok3)
        f.run(listOf("r3"), ProfileMode.PREVIEW)
        f.reads.size shouldBe 0
        f.commands.size shouldBe 0
    }

    @Test
    fun `INV-AC-26819 R10 a planned profile is refused before any relaxation is read`() {
        val f = fixture(ok3)
        f.run(listOf("r3"), ref = "PRF-3") shouldBe ApplyOperatingProfileOutcome.NotAvailable(ProfileStatus.PLANNED)
        f.reads.size shouldBe 0
        f.commands.size shouldBe 0
    }
}
