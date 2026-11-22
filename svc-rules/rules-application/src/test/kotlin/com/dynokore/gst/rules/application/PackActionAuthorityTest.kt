package com.dynokore.gst.rules.application

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** The tenant-scope method of [PackActionAuthority] (INV-Q-11688 A); fakes only. */
class PackActionAuthorityTest {
    private val own = TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val other = TenantId(UUID.fromString("22222222-2222-4222-8222-222222222222"))

    @Test
    fun `a lambda authority answers holdsTenant false`() {
        val authority = PackActionAuthority { _, _, _ -> true }
        authority.holdsTenant("U1", own, "mapping-pack.approve") shouldBe false
        authority.holdsTenant("U2", other, "mapping-pack.author") shouldBe false
    }

    @Test
    @Suppress("ObjectLiteralToLambda")
    fun `an authority that overrides only holds never grants at tenant scope`() {
        val authority =
            object : PackActionAuthority {
                override fun holds(
                    actor: String,
                    entityRef: String,
                    permission: String,
                ) = true
            }
        authority.holds("U1", "E1", "mapping-pack.approve") shouldBe true
        authority.holdsTenant("U1", own, "mapping-pack.approve") shouldBe false
    }

    private val tenantOnly =
        object : PackActionAuthority {
            override fun holds(
                actor: String,
                entityRef: String,
                permission: String,
            ) = false

            override fun holdsTenant(
                actor: String,
                tenant: TenantId,
                permission: String,
            ) = tenant == own && permission == "mapping-pack.approve"
        }

    @Test
    fun `an overriding authority answers true for its own tenant and permission`() {
        tenantOnly.holdsTenant("U1", own, "mapping-pack.approve") shouldBe true
    }

    @Test
    fun `another tenant answers false`() {
        tenantOnly.holdsTenant("U1", other, "mapping-pack.approve") shouldBe false
    }

    @Test
    fun `a look-alike permission answers false`() {
        tenantOnly.holdsTenant("U1", own, "mapping-pack.approved") shouldBe false
    }
}
