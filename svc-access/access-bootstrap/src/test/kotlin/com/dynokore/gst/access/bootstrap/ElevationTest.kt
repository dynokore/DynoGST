package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.elevation.ElevationStatus
import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * INV-T-27477 (INV-AC-27476): the matching rule of [OperatorAccess.openElevation] on constructed records, with no
 * Spring context and no database. Row-level security is the second layer; this is the first (OWASP ASVS V4).
 */
class ElevationTest {
    private val r = IdentityRef("user:r")
    private val t1 = TenantRef("11111111-1111-1111-1111-111111111111")
    private val t2 = TenantRef("22222222-2222-2222-2222-222222222222")
    private val from = Instant.parse("2026-11-01T10:00:00Z")
    private val until = Instant.parse("2026-11-01T11:00:00Z")
    private val inside = Instant.parse("2026-11-01T10:30:00Z")

    private fun record(
        tenant: TenantRef = t1,
        subject: String = tenant.value,
        requester: IdentityRef = r,
        kind: ElevationKind = ElevationKind.ELEVATION,
        status: ElevationStatus = ElevationStatus.GRANTED,
    ) = ElevationRecord(
        kind.refPrefix + "1",
        tenant,
        ElevationAsk(kind, subject, requester, "CASE-1"),
        status,
        if (status == ElevationStatus.GRANTED) ValidityWindow(from, until) else null,
        if (status == ElevationStatus.PENDING) null else IdentityRef("user:officer"),
        0,
    )

    private fun open(
        rec: ElevationRecord,
        at: Instant = inside,
    ) = OperatorAccess.openElevation(listOf(rec), r, t1, at)

    @Test
    fun `INV-AC-27476 a granted unexpired elevation of the requester and tenant is returned`() {
        val rec = record()
        assertThat(open(rec)).isSameAs(rec)
    }

    @Test
    fun `INV-AC-27476 other-tenant record is not returned`() {
        assertThat(open(record(tenant = t2))).isNull()
    }

    @Test
    fun `INV-AC-27476 tenant-only record of tenant T2 with subject T1 is not returned`() {
        assertThat(open(record(tenant = t2, subject = t1.value))).isNull()
    }

    @Test
    fun `INV-AC-27476 subject-only record of tenant T1 with subject T2 is not returned`() {
        assertThat(open(record(tenant = t1, subject = t2.value))).isNull()
    }

    @Test
    fun `INV-AC-27476 a record of another requester is not returned`() {
        assertThat(open(record(requester = IdentityRef("user:other")))).isNull()
    }

    @Test
    fun `INV-AC-27476 a pending record is not returned`() {
        assertThat(open(record(status = ElevationStatus.PENDING))).isNull()
    }

    @Test
    fun `INV-AC-27476 a record after its window is not returned`() {
        assertThat(open(record(), at = until.plusSeconds(1))).isNull()
    }

    @Test
    fun `INV-AC-27476 a diagnostic invitation is not returned`() {
        assertThat(open(record(kind = ElevationKind.DIAGNOSTIC_INVITATION))).isNull()
    }
}
