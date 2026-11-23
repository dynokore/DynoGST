package com.dynokore.gst.evidence.application.artefact

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ListRenditionSampleChecksTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val u1 = Principal("user-1", PrincipalKind.HUMAN)
    private val noKey = Principal("user-2", PrincipalKind.HUMAN)
    private val reads = mutableListOf<String>()
    private val t0 = Instant.parse("2026-11-01T00:00:00Z")
    private val h1 = "1".repeat(HASH)
    private val h2 = "2".repeat(HASH)

    /** An in-memory store of (entity, type, row); the reader sees only SAMPLE_CHECK of the entity, like the port. */
    private inner class Rows(
        private val rows: List<Triple<String, String, SampleCheckRow>>,
    ) : SampleCheckReader {
        override fun page(
            tenant: TenantId,
            entityRef: String,
            limit: Int,
            after: SampleCheckPosition?,
        ): SampleCheckPage {
            reads += "page"
            val sorted =
                rows
                    .filter { it.first == entityRef && it.second == "SAMPLE_CHECK" }
                    .map { it.third }
                    .sortedWith(compareBy({ it.recordedAt }, { it.reference }, { it.hash }))
                    .filter { after == null || compare(it.position, after) > 0 }
            val page = sorted.take(limit)
            return SampleCheckPage(page, if (sorted.size > limit) page.last().position else null)
        }

        private fun compare(
            a: SampleCheckPosition,
            b: SampleCheckPosition,
        ) = compareValuesBy(a, b, { it.recordedAt }, { it.reference }, { it.hash })
    }

    private class FakeCursors : SampleCheckCursors {
        override fun issue(
            entityRef: String,
            userId: String,
            last: SampleCheckPosition,
            now: Instant,
        ) = "$entityRef|$userId|${last.recordedAt}|${last.reference}|${last.hash}"

        override fun open(
            token: String,
            entityRef: String,
            userId: String,
            now: Instant,
        ): SampleCheckPosition? {
            val p = token.split('|')
            return if (p.size == 5 && p[0] == entityRef && p[1] == userId) {
                SampleCheckPosition(Instant.parse(p[2]), p[3], p[4])
            } else {
                null
            }
        }
    }

    private fun row(
        entity: String,
        type: String,
        reference: String,
        hash: String,
        at: Instant,
    ) = Triple(entity, type, SampleCheckRow(type, reference, hash, at))

    private val e1a = row("E1", "SAMPLE_CHECK", "ref-a", h1, t0)
    private val e1b = row("E1", "SAMPLE_CHECK", "ref-b", h1, t0.plusSeconds(1))
    private val e1other = row("E1", "RENDITION", "ref-r", h1, t0)
    private val e2 = row("E2", "SAMPLE_CHECK", "ref-c", h1, t0)

    private fun replica(
        key: String = RENDITION_EVIDENCE_VIEW,
        current: Boolean = true,
        fallback: ReplicaFallback<AuthorizationRequest, AuthorityVerdict> =
            ReplicaFallback { _, _ -> com.dynokore.platform.serviceruntime.replica.FallbackResult.Unavailable },
    ): AuthorityReplica {
        val roles = OperationRoleMap { if (it == key) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap() }
        val replica = AuthorityReplica(clock, roles, fallback = fallback)
        replica.accept(
            AuthorityChanged(
                tenant,
                u1.id,
                1L,
                AuthorityRecord.RoleGrantRecord(
                    "g1",
                    RoleGrantState.ACTIVE,
                    RoleGrant("auditor", GrantScope.ENTITY, "E1", Instant.parse("2026-01-01T00:00:00Z"), null),
                ),
            ),
        )
        if (current) {
            replica.onSnapshotComplete(1)
            replica.observeHead(1)
        }
        return replica
    }

    @Suppress("LongParameterList") // every input of one list call, each defaulted to the happy case
    private fun list(
        who: Principal = u1,
        replica: AuthorityReplica = replica(),
        rows: List<Triple<String, String, SampleCheckRow>> = listOf(e1b, e1a, e1other, e2),
        entity: String = "E1",
        limit: Int = 100,
        cursor: String? = null,
    ) = ListRenditionSampleChecks(replica, Rows(rows), FakeCursors(), clock).list(tenant, who, entity, limit, cursor)

    @Test
    fun `INV-AC-27602 the page of E1 holds its two SAMPLE_CHECK rows by recordedAt reference hash`() {
        val page = list().shouldBeInstanceOf<ListRenditionSampleChecksResult.Page>()
        page.items shouldBe listOf(e1a.third, e1b.third)
        page.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-27602 equal recordedAt is ordered by reference then hash and one reference keeps both hashes`() {
        val x1 = row("E1", "SAMPLE_CHECK", "ref-x", h2, t0)
        val x2 = row("E1", "SAMPLE_CHECK", "ref-x", h1, t0)
        val a = row("E1", "SAMPLE_CHECK", "ref-a", h2, t0)
        val page = list(rows = listOf(x1, x2, a)).shouldBeInstanceOf<ListRenditionSampleChecksResult.Page>()
        page.items shouldBe listOf(a.third, x2.third, x1.third)
    }

    @Test
    fun `INV-AC-27602 limit 1 gives one row and a cursor for the second and then no cursor`() {
        val first = list(limit = 1).shouldBeInstanceOf<ListRenditionSampleChecksResult.Page>()
        first.items shouldBe listOf(e1a.third)
        val second =
            list(
                limit = 1,
                cursor = first.nextCursor,
            ).shouldBeInstanceOf<ListRenditionSampleChecksResult.Page>()
        second.items shouldBe listOf(e1b.third)
        second.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-27602 no key, a DEGRADED decision and a thrown exception are Forbidden and nothing is read`() {
        list(who = noKey) shouldBe ListRenditionSampleChecksResult.Forbidden
        list(replica = replica(key = "other.key")) shouldBe ListRenditionSampleChecksResult.Forbidden
        list(entity = "E2") shouldBe ListRenditionSampleChecksResult.Forbidden
        list(replica = replica(current = false)) shouldBe ListRenditionSampleChecksResult.Forbidden
        val throwing = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> error("boom") }
        list(replica = replica(current = false, fallback = throwing)) shouldBe ListRenditionSampleChecksResult.Forbidden
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27602 a look-alike key with a trailing space or another case is Forbidden`() {
        list(replica = replica(key = "rendition-evidence.view ")) shouldBe ListRenditionSampleChecksResult.Forbidden
        list(replica = replica(key = "Rendition-evidence.view")) shouldBe ListRenditionSampleChecksResult.Forbidden
        RENDITION_EVIDENCE_VIEW shouldBe "rendition-evidence.view"
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27602 limit 0 and 501 are Invalid, a foreign cursor is Invalid, and nothing is read`() {
        list(limit = 0) shouldBe ListRenditionSampleChecksResult.Invalid
        list(limit = 501) shouldBe ListRenditionSampleChecksResult.Invalid
        list(cursor = "junk") shouldBe ListRenditionSampleChecksResult.Invalid
        list(limit = 500).shouldBeInstanceOf<ListRenditionSampleChecksResult.Page>()
        reads shouldBe listOf("page")
    }

    private companion object {
        const val HASH = 64
    }
}
