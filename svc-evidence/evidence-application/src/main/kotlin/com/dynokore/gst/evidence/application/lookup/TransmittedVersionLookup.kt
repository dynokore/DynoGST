package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** What the store holds for one transmitted version; [Unreadable] is a record that cannot be trusted (fail closed). */
sealed interface StoredTransmittedVersion {
    class Valid(
        val submittedAt: Instant,
        val acknowledgementId: String?,
        val acknowledgedAt: Instant?,
        xml: ByteArray,
        val xmlSha256: String,
    ) : StoredTransmittedVersion {
        private val bytes: ByteArray = xml.copyOf()

        fun xml(): ByteArray = bytes.copyOf()
    }

    /** The bytes do not hash to the recorded digest, or a sealed link has no chain record. */
    data object Unreadable : StoredTransmittedVersion
}

/** Reads one transmitted version in a tenant transaction; null when no row exists for (tenant, document, version). */
fun interface TransmittedVersionReader {
    fun read(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): StoredTransmittedVersion?
}

sealed interface TransmittedVersionResult {
    @Suppress("LongParameterList") // the seven members of the contract answer
    class Found(
        val docUuid: UUID,
        val version: Int,
        val submittedAt: Instant,
        val acknowledgementId: String?,
        val acknowledgedAt: Instant?,
        xml: ByteArray,
        val xmlSha256: String,
    ) : TransmittedVersionResult {
        private val bytes: ByteArray = xml.copyOf()

        /** The XML exactly as transmitted; a copy on every call. */
        val xml: ByteArray get() = bytes.copyOf()
    }

    /** No row for (tenant, document, version): unknown version and another tenant's row alike. */
    data object NotFound : TransmittedVersionResult

    /** The permission is not held, or the decision is unavailable (fail closed); no store is read. */
    data object Forbidden : TransmittedVersionResult

    /** The stored record cannot be trusted; never bytes. */
    data object Unavailable : TransmittedVersionResult
}

/**
 * The lookup of one transmitted version (INV-REQ-645). Order: decide the permission [DOC_VIEW_CONTENT] for the
 * tenant (a decision that cannot be made is Forbidden), then read the version. It writes no audit entry and reads
 * the structured tier only.
 */
class TransmittedVersionLookup(
    private val replica: AuthorityReplica,
    private val reader: TransmittedVersionReader,
) {
    fun lookup(
        tenant: TenantId,
        principal: Principal,
        docUuid: UUID,
        version: Int,
    ): TransmittedVersionResult {
        if (!allowed(tenant, principal)) return TransmittedVersionResult.Forbidden
        return when (val stored = reader.read(tenant, docUuid, version)) {
            null -> {
                TransmittedVersionResult.NotFound
            }

            StoredTransmittedVersion.Unreadable -> {
                TransmittedVersionResult.Unavailable
            }

            is StoredTransmittedVersion.Valid -> {
                TransmittedVersionResult.Found(
                    docUuid,
                    version,
                    stored.submittedAt,
                    stored.acknowledgementId,
                    stored.acknowledgedAt,
                    stored.xml(),
                    stored.xmlSha256,
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // any failure of the decision is a denial: fail closed
    private fun allowed(
        tenant: TenantId,
        principal: Principal,
    ): Boolean =
        try {
            replica.decideTenant(tenant, principal, DOC_VIEW_CONTENT).allowed
        } catch (_: Exception) {
            false
        }
}
