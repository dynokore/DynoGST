package com.dynokore.gst.rules.application

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The AcceptRateSource use case (INV-REQ-504); fakes only. */
class AcceptRateSourceTest {
    private val t1 = TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val today = LocalDate.of(2026, 11, 20)
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)

    private class Store : RateSourceAcceptanceStore {
        val writes = mutableListOf<RateSourceAcceptance>()

        override fun replace(acceptance: RateSourceAcceptance) {
            writes += acceptance
        }

        override fun byTenant(tenant: TenantId) = writes.filter { it.tenant == tenant }
    }

    private class Authority(
        private val tenantGrant: Boolean,
        private val entityGrant: Boolean = false,
    ) : PackActionAuthority {
        val asked = mutableListOf<String>()

        override fun holds(
            actor: String,
            entityRef: String,
            permission: String,
        ): Boolean {
            asked += permission
            return entityGrant
        }

        override fun holdsTenant(
            actor: String,
            tenant: TenantId,
            permission: String,
        ): Boolean {
            asked += permission
            return tenantGrant && actor == "U1" && permission == "mapping-pack.approve"
        }
    }

    private val store = Store()
    private var inferred: Set<String>? = setOf("S1")

    private fun run(
        authority: Authority = Authority(true),
        source: String = "S1",
        rationale: String = "x".repeat(20),
        expires: LocalDate = today.plusDays(1),
    ) = AcceptRateSource(authority, { _, _ -> inferred }, store, clock)
        .accept(AcceptRateSourceCommand(t1, "U1", source, rationale, expires))

    private fun refused(
        result: AcceptRateSourceResult,
        reason: RefusalReason,
    ) {
        result shouldBe AcceptRateSourceResult.Refused(reason, "U1")
        store.writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27497 case 1 a holder stores one row with the caller as accepted_by`() {
        val result = run()
        store.writes.size shouldBe 1
        store.writes[0].tenant shouldBe t1
        store.writes[0].sourceId shouldBe "S1"
        store.writes[0].acceptedBy shouldBe "U1"
        (result as AcceptRateSourceResult.Accepted).acceptance.acceptedBy shouldBe "U1"
    }

    @Test
    fun `INV-AC-27497 case 2 a rationale of 500 characters is stored`() {
        run(rationale = "x".repeat(500))
        store.writes.size shouldBe 1
    }

    @Test
    fun `INV-AC-27497 case 3 a rationale of 501 characters is refused`() {
        refused(run(rationale = "x".repeat(501)), RefusalReason.INPUT_INVALID)
    }

    @Test
    fun `INV-AC-27497 case 3 a blank rationale is refused`() {
        refused(run(rationale = "   "), RefusalReason.INPUT_INVALID)
    }

    @Test
    fun `INV-AC-27497 case 4 an expiry of today is refused`() {
        refused(run(expires = today), RefusalReason.INPUT_INVALID)
    }

    @Test
    fun `INV-AC-27497 case 5 a source that is not INFERRED is refused as unknown`() {
        refused(run(source = "S2"), RefusalReason.UNKNOWN_ENTRY)
    }

    @Test
    fun `INV-AC-27497 case 5 the unknown source S9 is refused`() {
        refused(run(source = "S9"), RefusalReason.UNKNOWN_ENTRY)
    }

    @Test
    fun `INV-AC-27497 case 6 a caller without the permission is refused and the key is asked`() {
        val authority = Authority(false)
        run(authority) shouldBe AcceptRateSourceResult.NotPermitted("U1")
        store.writes shouldBe emptyList()
        authority.asked shouldBe listOf("mapping-pack.approve")
    }

    @Test
    fun `INV-AC-27497 look-alike source S10 against S1 is refused as unknown`() {
        refused(run(source = "S10"), RefusalReason.UNKNOWN_ENTRY)
    }

    @Test
    fun `INV-AC-27497 a rationale of exactly 500 characters is accepted`() {
        run(rationale = "y".repeat(500)) shouldBe
            AcceptRateSourceResult.Accepted(
                RateSourceAcceptance(t1, "S1", "y".repeat(500), today.plusDays(1), "U1"),
            )
    }

    @Test
    fun `INV-AC-27497 a rationale of exactly 501 characters writes nothing`() {
        refused(run(rationale = "y".repeat(501)), RefusalReason.INPUT_INVALID)
    }

    @Test
    fun `INV-AC-27497 an entity grant without a tenant grant is refused`() {
        val authority = Authority(tenantGrant = false, entityGrant = true)
        run(authority) shouldBe AcceptRateSourceResult.NotPermitted("U1")
        store.writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27497 a scope pack that cannot answer is refused as unavailable`() {
        inferred = null
        refused(run(), RefusalReason.RATE_SOURCE_UNAVAILABLE)
    }
}
