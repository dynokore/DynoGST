package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.authorise.OperatorOverlay
import com.dynokore.gst.access.application.authorise.PlatformRead
import com.dynokore.gst.access.application.consultant.ConsultantGrantKeys
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** The exact exemption of `consultant-grant.request` on the scope platform from the invitation rule (INV-REQ-888). */
class OperatorOverlayExemptionTest {
    private val u1 = IdentityRef("u1")
    private val now = Instant.parse("2026-11-22T00:00:00Z")
    private val allow = Decision.Allow(Role.DEPLOYMENT_OPERATOR)
    private val request = ConsultantGrantKeys.REQUEST

    private val others =
        listOf(
            "support-bundle.export",
            "consultant-grant.approve",
            "user.manage",
            "Consultant-grant.request",
            "consultant-grant.requests",
        ).map { Permission(it) }

    private val elevations =
        object : Elevations {
            override fun findPending(ask: ElevationAsk): ElevationRecord? = error("not used")

            override fun insert(ask: ElevationAsk): ElevationRecord = error("not used")

            override fun lock(ref: String): ElevationRecord? = error("not used")

            override fun update(
                before: ElevationRecord,
                after: ElevationRecord,
            ) = error("not used")

            override fun findByRequester(requester: IdentityRef): List<ElevationRecord> = error("invitation lookup")
        }

    private val grants =
        object : GrantRepository {
            override fun findByIdentity(identity: IdentityRef): List<Grant> = error("not used")

            override fun find(ref: GrantRef): Grant? = error("not used")

            override fun findByRepeatKey(
                identity: IdentityRef,
                roleWire: String,
                scope: Scope,
            ): Grant? = error("not used")

            override fun insert(grant: Grant) = error("not used")

            override fun update(
                before: Grant,
                after: Grant,
            ) = error("not used")

            override fun lockIdentity(
                identity: IdentityRef,
                kind: GranteeKind,
            ) = error("not used")

            override fun nextAuthorityVersion(): Long = error("not used")

            override fun nextGrantRef(): GrantRef = error("not used")
        }

    private val overlay =
        OperatorOverlay(
            elevations,
            object : GrantAudit {
                override fun record(entry: GrantAuditEntry) = error("audit written")
            },
            object : PlatformRead {
                override fun <T> read(block: () -> T): T = error("not used")
            },
            grants,
            PermissionMatrix(emptyList(), emptyMap()),
        )

    /** The invitation lookup is reached (the fake throws) and no Allow comes back. */
    private fun reachesLookup(
        decision: Decision,
        permission: Permission,
        tenant: TenantRef?,
    ) {
        val raised = runCatching { overlay.apply(decision, u1, permission, tenant, now) }
        assertFalse(raised.getOrNull() is Decision.Allow)
        assertThrows(IllegalStateException::class.java) { raised.getOrThrow() }
    }

    @Test
    fun `INV-AC-27614 request key on platform stays ALLOW and reads no record`() {
        assertEquals(allow, overlay.apply(allow, u1, request, null, now))
    }

    @Test
    fun `INV-AC-27614 request key at a tenant reaches the invitation lookup`() {
        reachesLookup(allow, request, TenantRef("T1"))
    }

    @Test
    fun `INV-AC-27614 every other key and look-alike on platform reaches the invitation lookup`() {
        others.forEach { reachesLookup(allow, it, null) }
    }

    @Test
    fun `INV-AC-27614 the exempt set is exactly the request key`() {
        assertEquals(setOf(Permission("consultant-grant.request")), OperatorOverlay.OPEN_WITHOUT_INVITATION)
    }

    @Test
    fun `INV-AC-27614 an allow of another role is returned unchanged for every key`() {
        val other = Decision.Allow(Role.TENANT_ADMIN)
        (others + request).forEach {
            assertEquals(other, overlay.apply(other, u1, it, null, now))
            assertEquals(other, overlay.apply(other, u1, it, TenantRef("T1"), now))
        }
    }
}
