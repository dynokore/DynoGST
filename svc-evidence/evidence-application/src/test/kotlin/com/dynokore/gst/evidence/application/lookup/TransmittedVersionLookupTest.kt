package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
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

class TransmittedVersionLookupTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val user = Principal("user-42", PrincipalKind.HUMAN)
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val bytes = byteArrayOf(0x3C, 0xC3.toByte(), 0x28, 0x3E)
    private val digest = "ab".repeat(32)
    private val stored = StoredTransmittedVersion.Valid(at, "ACK-1", at.plusSeconds(60), bytes, digest)
    private val readTenants = mutableListOf<TenantId>()
    private val keys = mutableListOf<String>()

    private fun reader(answer: StoredTransmittedVersion?) =
        TransmittedVersionReader { tenant, _, _ ->
            readTenants += tenant
            answer
        }

    /** The replica grants only [key]; [current] false makes the decision unavailable, [throwing] makes it throw. */
    private fun replica(
        key: String = DOC_VIEW_CONTENT,
        current: Boolean = true,
        throwing: Boolean = false,
    ): AuthorityReplica {
        val roles =
            OperationRoleMap {
                keys += it
                if (it == key) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap()
            }
        val unavailable =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
                if (throwing) error("authority down")
                FallbackResult.Unavailable
            }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        replica.accept(
            AuthorityChanged(
                t1,
                user.id,
                1,
                AuthorityRecord.RoleGrantRecord(
                    "g1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        "auditor",
                        GrantScope.TENANT,
                        t1.value.toString(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        null,
                    ),
                ),
            ),
        )
        if (current) {
            replica.onSnapshotComplete(1)
            replica.observeHead(1)
        }
        return replica
    }

    private fun lookup(
        answer: StoredTransmittedVersion?,
        replica: AuthorityReplica = replica(),
    ) = TransmittedVersionLookup(replica, reader(answer)).lookup(t1, user, doc, 2)

    @Test
    fun `INV-AC-26262 an allowed caller gets Found with the identifiers, the bytes and the digest of the record`() {
        val found = lookup(stored).shouldBeInstanceOf<TransmittedVersionResult.Found>()
        found.docUuid shouldBe doc
        found.version shouldBe 2
        found.submittedAt shouldBe at
        found.xml.contentEquals(bytes) shouldBe true
        found.xmlSha256 shouldBe digest
        found.acknowledgementId shouldBe "ACK-1"
        found.acknowledgedAt shouldBe at.plusSeconds(60)
    }

    @Test
    fun `INV-AC-26262 a denied decision is Forbidden and the reader is not called`() {
        lookup(stored, replica(key = "other.key")) shouldBe TransmittedVersionResult.Forbidden
        readTenants.size shouldBe 0
    }

    @Test
    fun `INV-AC-26262 an unavailable decision is Forbidden and the reader is not called`() {
        lookup(stored, replica(current = false)) shouldBe TransmittedVersionResult.Forbidden
        lookup(stored, replica(current = false, throwing = true)) shouldBe TransmittedVersionResult.Forbidden
        readTenants.size shouldBe 0
    }

    @Test
    fun `INV-AC-26262 the look-alike keys do not allow the lookup`() {
        for (lookAlike in listOf("enquiry-lookup.by-acknowledgement", "$DOC_VIEW_CONTENT ")) {
            lookup(stored, replica(key = lookAlike)) shouldBe TransmittedVersionResult.Forbidden
        }
        readTenants.size shouldBe 0
    }

    @Test
    fun `INV-AC-26262 a null record is NotFound and an unreadable record is Unavailable without bytes`() {
        lookup(null) shouldBe TransmittedVersionResult.NotFound
        lookup(StoredTransmittedVersion.Unreadable) shouldBe TransmittedVersionResult.Unavailable
    }

    @Test
    fun `INV-AC-26262 only the key doc dot view dot content is decided and the reader gets the caller's tenant`() {
        lookup(stored)
        keys.distinct() shouldBe listOf("doc.view.content")
        (DOC_VIEW_CONTENT == "doc.view.content") shouldBe true
        readTenants shouldBe listOf(t1)
    }
}
