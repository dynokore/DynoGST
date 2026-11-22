package com.dynokore.gst.consoleBff.adapters.privacy

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-27140 (authority half): every decision goes to the scripted fallback, which records the request. */
class ReplicaPrivacyAuthorityTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-14T10:00:00Z")
    }

    private val clock = FixedClock()
    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val seen = mutableListOf<AuthorizationRequest>()

    private fun authority(answer: () -> FallbackResult<AuthorityVerdict>) =
        ReplicaPrivacyAuthority(
            AuthorityReplica(
                clock,
                operationRoles = OperationRoleMap { emptyMap() },
                fallback =
                    ReplicaFallback { request, _ ->
                        seen += request
                        answer()
                    },
            ),
            clock,
            Duration.ofSeconds(1),
        )

    @Test
    fun `INV-AC-27140 holds is true only for an allowed decision and asks the key privacy-request dot handle`() {
        authority { FallbackResult.Found(AuthorityVerdict(true)) }.holds(tenant, "user-1", "entity-9") shouldBe true
        seen.size shouldBe 1
        val request = seen.single()
        request.tenant shouldBe TenantId.of(tenant)
        request.principal.id shouldBe "user-1"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.entityRef shouldBe "entity-9"
        request.action shouldBe "privacy-request.handle"
        seen.none { it.action == "privacy-request.handle2" } shouldBe true
    }

    @Test
    fun `INV-AC-27140 holds is false for a denied decision`() {
        authority { FallbackResult.Found(AuthorityVerdict(false)) }.holds(tenant, "u", "e") shouldBe false
    }

    @Test
    fun `INV-AC-27140 holds is false for a null decision`() {
        authority { FallbackResult.NotFound }.holds(tenant, "u", "e") shouldBe false
    }

    @Test
    fun `INV-AC-27140 holds is false for a DEGRADED decision`() {
        authority { FallbackResult.Unavailable }.holds(tenant, "u", "e") shouldBe false
    }

    @Test
    fun `INV-AC-27140 holds is false when decide throws`() {
        authority { error("authority down") }.holds(tenant, "u", "e") shouldBe false
        seen.size shouldBe 1
    }

    @Test
    fun `INV-AC-27140 holds asks decide on every call and never caches`() {
        var allowed = true
        val subject = authority { FallbackResult.Found(AuthorityVerdict(allowed)) }
        subject.holds(tenant, "u", "e") shouldBe true
        allowed = false
        subject.holds(tenant, "u", "e") shouldBe false
        seen.size shouldBe 2
    }

    @Test
    fun `INV-AC-27140 holds is false for a tenant id that is not a tenant and asks nothing`() {
        authority { FallbackResult.Found(AuthorityVerdict(true)) }.holds("not-a-tenant", "u", "e") shouldBe false
        seen.size shouldBe 0
    }
}
