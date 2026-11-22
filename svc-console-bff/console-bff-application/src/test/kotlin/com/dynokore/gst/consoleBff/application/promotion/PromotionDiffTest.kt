package com.dynokore.gst.consoleBff.application.promotion

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromotionDiffTest {
    private val change = ConfigurationChange("rules/r1", "CHANGED", "a", "b")

    private class Fake(
        val reply: OwnerReply<List<ConfigurationChange>>,
    ) : PromotionDiffPort {
        val calls = mutableListOf<String>()

        override fun diff(bundleId: String): OwnerReply<List<ConfigurationChange>> {
            calls += bundleId
            return reply
        }
    }

    private val rules = Fake(OwnerReply.Ok(listOf(change)))
    private val masterdata = Fake(OwnerReply.Ok(emptyList()))

    @Test
    fun `INV-AC-26953 mapping pack returns the one change with the dry run approval path`() {
        val r = PromotionDiff(rules, masterdata).diff("b1", "mapping-pack")
        r shouldBe
            PromotionDiffResult.Diff(
                ConfigurationDiff("b1", "mapping-pack", listOf(change), ApprovalPath.MAPPING_DRY_RUN_AND_APPROVAL),
            )
        rules.calls shouldBe listOf("b1")
        masterdata.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26953 identity triple returns no change with the dual control approval path`() {
        val r = PromotionDiff(rules, masterdata).diff("b1", "identity-triple")
        r shouldBe
            PromotionDiffResult.Diff(
                ConfigurationDiff("b1", "identity-triple", emptyList(), ApprovalPath.IDENTITY_TRIPLE_DUAL_CONTROL),
            )
        masterdata.calls shouldBe listOf("b1")
        rules.calls shouldBe emptyList()
    }

    private val outside =
        listOf("", "mapping_pack", "Mapping-Pack", "mapping-pack ", "other", "*", "mapping-pack,identity-triple")

    @Test
    fun `INV-AC-26953 a domain outside the two is refused and no port is called`() {
        for (d in outside) {
            PromotionDiff(rules, masterdata).diff("b1", d) shouldBe PromotionDiffResult.UnknownDomain(d)
        }
        rules.calls shouldBe emptyList()
        masterdata.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26953 an owning service failure is a typed failure and not an empty diff`() {
        val unavailable = OwnerReply.Unavailable("timeout")
        val r = PromotionDiff(Fake(unavailable), masterdata).diff("b1", "mapping-pack")
        r shouldBe PromotionDiffResult.OwnerFailed(unavailable)
        val refused =
            OwnerReply.Refused(
                OwnerRefusal(422, "X", "t", "BLOCK", "TERMINAL", "OWNER", null),
            )
        PromotionDiff(rules, Fake(refused)).diff("b1", "identity-triple") shouldBe
            PromotionDiffResult.OwnerFailed(refused)
    }

    @Test
    fun `INV-AC-26953 the use case holds no store`() {
        val types = PromotionDiff::class.java.declaredFields.map { it.type }
        types shouldBe listOf(PromotionDiffPort::class.java, PromotionDiffPort::class.java)
    }
}
