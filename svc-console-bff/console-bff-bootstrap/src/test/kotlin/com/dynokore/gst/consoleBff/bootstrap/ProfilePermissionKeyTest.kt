package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.PermissionKind
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.consoleBff.application.profile.OPERATING_PROFILE_APPLY
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-REQ-1245 item 6, owner ruling 2026-11-22 option A: who may apply an operating profile. */
class ProfilePermissionKeyTest {
    private val expected =
        mapOf("tenant.admin" to RoleAllowance.PERMITTED, "entity.admin" to RoleAllowance.WITHIN_SCOPE)

    @Test
    fun `INV-AC-27578 SAAS holds exactly tenant admin PERMITTED and entity admin WITHIN_SCOPE`() {
        OperationRoles.rolesFor(DeploymentForm.SAAS, OPERATING_PROFILE_APPLY) shouldBe expected
    }

    @Test
    fun `INV-AC-27578 PRIVATE holds exactly tenant admin PERMITTED and entity admin WITHIN_SCOPE`() {
        OperationRoles.rolesFor(DeploymentForm.PRIVATE, OPERATING_PROFILE_APPLY) shouldBe expected
    }

    @Test
    fun `INV-AC-27578 the gate constant equals the key and the key is a WRITE key`() {
        OPERATING_PROFILE_APPLY shouldBe "operating-profile.apply"
        RolePermissions.kind(OPERATING_PROFILE_APPLY) shouldBe PermissionKind.WRITE
    }
}
