package com.dynokore.gst.consoleBff.adapters.profile

import com.dynokore.gst.consoleBff.application.profile.OPERATING_PROFILE_APPLY
import com.dynokore.gst.consoleBff.application.profile.ProfileScope
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-27577: the replica is never caught up, so decisions go to a scripted recording fallback. */
class ReplicaProfileAuthorityTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-23T10:00:00Z")
    }

    private val clock = FixedClock()
    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val seen = mutableListOf<AuthorizationRequest>()

    private fun authority(answer: (AuthorizationRequest) -> FallbackResult<AuthorityVerdict>) =
        ReplicaProfileAuthority(
            AuthorityReplica(
                clock,
                operationRoles = OperationRoleMap { emptyMap() },
                fallback =
                    ReplicaFallback { request, _ ->
                        seen += request
                        answer(request)
                    },
            ),
            clock,
            Duration.ofSeconds(1),
        )

    private val allow = { _: AuthorizationRequest -> FallbackResult.Found(AuthorityVerdict(true)) }

    @Test
    fun `INV-AC-27577 allow gives true for scope ENTITY on E1 and the request carries the whole tuple`() {
        authority(allow).holds(tenant, "user-1", "E1", ProfileScope.ENTITY) shouldBe true
        seen.size shouldBe 1
        val request = seen.single()
        request.tenant shouldBe TenantId.of(tenant)
        request.principal.id shouldBe "user-1"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.entityRef shouldBe "E1"
        request.action shouldBe "operating-profile.apply"
        request.level shouldBe ScopeLevel.ENTITY
    }

    @Test
    fun `INV-AC-27577 scope TENANT sends level TENANT`() {
        authority(allow).holds(tenant, "user-1", "E1", ProfileScope.TENANT) shouldBe true
        seen.single().level shouldBe ScopeLevel.TENANT
    }

    @Test
    fun `INV-AC-27577 the action equals the key exactly and no role name is sent`() {
        authority(allow).holds(tenant, "u", "E1", ProfileScope.ENTITY)
        val request = seen.single()
        request.action shouldBe OPERATING_PROFILE_APPLY
        request.action shouldBe "operating-profile.apply"
        listOf("tenant.admin", "entity.admin", "admin").any { request.action.contains(it) } shouldBe false
        listOf(request.principal.id, request.entityRef).any { it.contains("admin") } shouldBe false
    }

    @Test
    fun `INV-AC-27577 a deny gives false`() {
        val deny = { _: AuthorizationRequest -> FallbackResult.Found(AuthorityVerdict(false)) }
        authority(deny).holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe false
        authority(deny).holds(tenant, "u", "E1", ProfileScope.TENANT) shouldBe false
    }

    @Test
    fun `INV-AC-27577 a DEGRADED decision gives false`() {
        authority { FallbackResult.Unavailable }.holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe false
        authority { FallbackResult.Unavailable }.holds(tenant, "u", "E1", ProfileScope.TENANT) shouldBe false
    }

    @Test
    fun `INV-AC-27577 a decision that cannot be made gives false`() {
        authority { FallbackResult.NotFound }.holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe false
    }

    @Test
    fun `INV-AC-27577 holds is false when decide throws and no exception escapes`() {
        authority { error("authority down") }.holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe false
        seen.size shouldBe 1
        authority { error("x") }.holds("not-a-tenant", "u", "E1", ProfileScope.TENANT) shouldBe false
    }

    @Test
    fun `INV-AC-27577 a replica that allows only level ENTITY on E1 gives false for TENANT and true for ENTITY`() {
        val onlyEntityE1 = { r: AuthorizationRequest ->
            FallbackResult.Found(AuthorityVerdict(r.level == ScopeLevel.ENTITY && r.entityRef == "E1"))
        }
        authority(onlyEntityE1).holds(tenant, "u", "E1", ProfileScope.TENANT) shouldBe false
        authority(onlyEntityE1).holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe true
    }

    @Test
    fun `INV-AC-27577 a replica that allows only entity E2 gives false for E1`() {
        val onlyE2 = { r: AuthorizationRequest -> FallbackResult.Found(AuthorityVerdict(r.entityRef == "E2")) }
        authority(onlyE2).holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe false
        authority(onlyE2).holds(tenant, "u", "E2", ProfileScope.ENTITY) shouldBe true
    }

    @Test
    fun `INV-AC-27577 every call asks the replica again and is never cached`() {
        val a = authority(allow)
        a.holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe true
        a.holds(tenant, "u", "E1", ProfileScope.ENTITY) shouldBe true
        seen.size shouldBe 2
    }
}
