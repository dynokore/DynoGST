package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.application.MappingPackBundleDiffResult.Changes
import com.dynokore.gst.rules.application.MappingPackBundleDiffResult.DuplicateInternalTaxCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MappingPackBundleDiffTest {
    private fun rule(
        code: String,
        rate: String,
    ) = MappingBundleRule(code, "d-$code", "TX", rate, "PRIMARY", null)

    private val diff = MappingPackBundleDiff()

    @Test
    fun `INV-AC-26950 one CHANGED rate with current and proposed, the unchanged rule not listed`() {
        val active = listOf(rule("SALES-STD", "9"), rule("ZERO", "0"))
        val part = listOf(rule("SALES-STD", "8"), rule("ZERO", "0"))
        diff.diff(part, active) shouldBe
            Changes(listOf(MappingPackChange("rules/SALES-STD/rate", MappingChangeKind.CHANGED, "9", "8")))
    }

    @Test
    fun `INV-AC-26950 a rule only in the part is ADDED and only in the active pack is REMOVED`() {
        val result = diff.diff(listOf(rule("NEW", "9")), listOf(rule("OLD", "9")))
        result shouldBe
            Changes(
                listOf(
                    MappingPackChange("rules/NEW", MappingChangeKind.ADDED),
                    MappingPackChange("rules/OLD", MappingChangeKind.REMOVED),
                ),
            )
    }

    @Test
    fun `INV-AC-26950 identical lists give an empty result`() {
        val list = listOf(rule("A", "9"), rule("B", "0"))
        diff.diff(list, list.toList()) shouldBe Changes(emptyList())
    }

    @Test
    fun `INV-AC-26950 every property is compared and reported under its own path`() {
        val c = MappingBundleRule("X", "d", "TX", "9", "PRIMARY", "c1")
        val p = MappingBundleRule("X", "d2", "TY", "8", "SECONDARY", null)
        diff.diff(listOf(p), listOf(c)) shouldBe
            Changes(
                listOf(
                    MappingPackChange("rules/X/description", MappingChangeKind.CHANGED, "d", "d2"),
                    MappingPackChange("rules/X/irasCode", MappingChangeKind.CHANGED, "TX", "TY"),
                    MappingPackChange("rules/X/rate", MappingChangeKind.CHANGED, "9", "8"),
                    MappingPackChange("rules/X/sourceClass", MappingChangeKind.CHANGED, "PRIMARY", "SECONDARY"),
                    MappingPackChange("rules/X/citation", MappingChangeKind.CHANGED, "c1", null),
                ),
            )
    }

    @Test
    fun `INV-AC-26950 a duplicate internalTaxCode is refused with a typed failure, not merged`() {
        diff.diff(listOf(rule("A", "9"), rule("A", "8")), listOf(rule("A", "9"))) shouldBe
            DuplicateInternalTaxCode("A", MappingPackBundleDiffResult.Side.BUNDLE)
        diff.diff(listOf(rule("A", "9")), listOf(rule("A", "9"), rule("A", "9"))) shouldBe
            DuplicateInternalTaxCode("A", MappingPackBundleDiffResult.Side.ACTIVE)
    }

    @Test
    fun `INV-AC-26950 the function takes only value lists, no store, so it cannot write`() {
        val method = MappingPackBundleDiff::class.java.methods.single { it.name == "diff" }
        method.parameterTypes.toList() shouldBe listOf(List::class.java, List::class.java)
        MappingPackBundleDiff::class.java.declaredFields.size shouldBe 0
    }
}
