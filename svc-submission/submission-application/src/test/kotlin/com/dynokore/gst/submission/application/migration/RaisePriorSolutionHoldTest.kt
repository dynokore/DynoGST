package com.dynokore.gst.submission.application.migration

import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RaisePriorSolutionHoldTest {
    private class Memory : PriorSolutionHoldStore {
        val rows = linkedMapOf<Triple<TenantId, String, Int>, PriorSolutionHold>()
        val resolved = mutableSetOf<Triple<TenantId, String, Int>>()

        override fun raise(hold: PriorSolutionHold) =
            rows.getOrPut(Triple(hold.tenant, hold.docUuid, hold.version)) { hold }

        override fun open(
            tenant: TenantId,
            docUuid: String,
            version: Int,
        ): PriorSolutionHold? = Triple(tenant, docUuid, version).let { k -> rows[k]?.takeIf { k !in resolved } }
    }

    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())
    private val store = Memory()
    private var now = Instant.parse("2026-11-25T01:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val raise = RaisePriorSolutionHold(store, clock)
    private val sub017 =
        PriorSolutionDecision.HoldForCorrection("SUB-017", HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT)

    @Test
    fun `INV-AC-28447 the hold is raised once per version and reads open until a resolution row exists`() {
        val first = raise.execute(t1, "D", 1, "E1", sub017)
        now = now.plusSeconds(60)
        raise.execute(t1, "D", 1, "E9", sub017) shouldBe first
        store.rows.size shouldBe 1
        first.entityRef shouldBe "E1"
        first.reason shouldBe HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT
        first.code shouldBe "SUB-017"
        first.raisedAt shouldBe Instant.parse("2026-11-25T01:00:00Z")
        store.open(t1, "D", 1) shouldBe first
        store.open(t1, "D", 2) shouldBe null
        store.open(t2, "D", 1) shouldBe null
        store.resolved += Triple(t1, "D", 1)
        store.open(t1, "D", 1) shouldBe null
    }

    @Test
    fun `INV-AC-28447 look-alikes the same doc other version and other tenant are separate holds`() {
        raise.execute(t1, "D", 1, "E1", sub017)
        raise.execute(t1, "D", 2, "E1", sub017)
        raise.execute(t2, "D", 1, "E1", sub017)
        store.rows.size shouldBe 3
    }

    @Test
    fun `INV-AC-28447 a decision whose code is not SUB-017 raises nothing and fails`() {
        listOf("SUB-016", "sub-017", "SUB-017 ", "").forEach {
            assertThrows<IllegalArgumentException> {
                val decision = PriorSolutionDecision.HoldForCorrection(it, HoldReason.NO_PREDECESSOR_ESTABLISHED)
                raise.execute(t1, "D", 1, "E1", decision)
            }
        }
        store.rows.size shouldBe 0
    }
}
