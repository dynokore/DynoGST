package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SealedPayload.Companion.SEALED_PAYLOAD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SubmissionIndex.Companion.SUBMISSION_INDEX
import com.dynokore.gst.evidence.application.lookup.StoredTransmittedVersion
import com.dynokore.gst.evidence.application.lookup.TransmittedVersionReader
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * One transmitted version from the structured tier (INV-REQ-645). The `sealed_payload` links of (tenant, document,
 * version) are located through `applied_record` and read from the chain with
 * [EvidenceLedger.findByEntityAndPeriod], as [JooqChainRecordReader] does. The record of the highest attempt wins
 * (every attempt carries the same bytes). The bytes are the decoded `payloadBase64`; their SHA-256 must equal the
 * recorded `payloadSha256`, otherwise, and for a link without its `applied_record` row or chain record, the answer
 * is [StoredTransmittedVersion.Unreadable], never the bytes. No link is null.
 */
class JooqTransmittedVersionReader(
    private val dsl: DSLContext,
    private val ledgers: Map<RecordKind, EvidenceLedger>,
) : TransmittedVersionReader {
    private class Attempt(
        val number: Int,
        val digest: String,
        val submittedAt: Instant,
        val bytes: ByteArray,
    )

    override fun read(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): StoredTransmittedVersion? {
        val events =
            dsl
                .select(SEALED_PAYLOAD.SOURCE_EVENT_ID)
                .from(SEALED_PAYLOAD)
                .where(SEALED_PAYLOAD.TENANT_ID.eq(tenant.value))
                .and(SEALED_PAYLOAD.DOC_UUID.eq(docUuid))
                .and(SEALED_PAYLOAD.VERSION.eq(version))
                .fetch(SEALED_PAYLOAD.SOURCE_EVENT_ID)
                .filterNotNull()
        if (events.isEmpty()) return null
        val attempts = attempts(tenant, docUuid, version, events)
        val best = if (attempts == null) null else attempts.maxByOrNull { it.number }
        return if (best == null || sha256Hex(best.bytes) != best.digest) {
            StoredTransmittedVersion.Unreadable
        } else {
            val ack =
                dsl
                    .select(SUBMISSION_INDEX.ACK_ID, SUBMISSION_INDEX.ACKNOWLEDGED_AT)
                    .from(SUBMISSION_INDEX)
                    .where(SUBMISSION_INDEX.TENANT_ID.eq(tenant.value))
                    .and(SUBMISSION_INDEX.DOC_UUID.eq(docUuid))
                    .and(SUBMISSION_INDEX.VERSION.eq(version))
                    .fetchOne()
            StoredTransmittedVersion.Valid(
                best.submittedAt,
                ack?.get(SUBMISSION_INDEX.ACK_ID),
                ack?.get(SUBMISSION_INDEX.ACKNOWLEDGED_AT)?.toInstant(),
                best.bytes,
                best.digest,
            )
        }
    }

    /** The parsed record of every link, or null when one has no `applied_record` row, chain record or valid content. */
    private fun attempts(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        events: List<String>,
    ): List<Attempt>? {
        val located =
            dsl
                .select(
                    APPLIED_RECORD.SOURCE_EVENT_ID,
                    APPLIED_RECORD.KIND,
                    APPLIED_RECORD.ENTITY_REF,
                    APPLIED_RECORD.PERIOD_REF,
                    APPLIED_RECORD.SEQ,
                ).from(APPLIED_RECORD)
                .where(APPLIED_RECORD.TENANT_ID.eq(tenant.value))
                .and(APPLIED_RECORD.SOURCE_EVENT_ID.`in`(events))
                .fetch()
        val ledger = ledgers[RecordKind.EVIDENCE_PAYLOAD]
        val sound =
            ledger != null &&
                located.size == events.size &&
                located.all { it[APPLIED_RECORD.KIND] == RecordKind.EVIDENCE_PAYLOAD.table }
        if (!sound) return null
        val parsed =
            located
                .groupBy { chainOf(it) }
                .flatMap { (chain, rows) ->
                    val seqs = rows.map { checkNotNull(it[APPLIED_RECORD.SEQ]) }.toSet()
                    checkNotNull(ledger)
                        .findByEntityAndPeriod(tenant, EntityRef(chain.first), PeriodRef(chain.second))
                        .filter { it.sequence in seqs }
                        .map { parse(it.content, docUuid, version) }
                }
        return if (parsed.size == events.size && parsed.none { it == null }) parsed.filterNotNull() else null
    }

    private fun attempt(
        number: Int,
        digest: String?,
        despatchedAt: String?,
        payload: String?,
    ): Attempt? =
        if (digest == null || despatchedAt == null || payload == null) {
            null
        } else {
            Attempt(number, digest, Instant.parse(despatchedAt), Base64.getDecoder().decode(payload))
        }

    private fun chainOf(row: Record): Pair<String, String> =
        checkNotNull(row[APPLIED_RECORD.ENTITY_REF]) to checkNotNull(row[APPLIED_RECORD.PERIOD_REF])

    private fun parse(
        content: ByteArray,
        docUuid: UUID,
        version: Int,
    ): Attempt? =
        try {
            val node = mapper.readTree(content)
            val sameVersion = node.text("docUuid") == docUuid.toString() && node.get("version")?.asInt(0) == version
            val number = node.get("attemptNumber")?.asInt(0) ?: 0
            val digest = node.text("payloadSha256")
            val despatchedAt = node.text("despatchedAt")
            val payload = node.text("payloadBase64")
            if (!sameVersion || number < 1) {
                null
            } else {
                attempt(number, digest, despatchedAt, payload)
            }
        } catch (_: JacksonException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
