package com.dynokore.gst.payload.adapters.events

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PayloadReadyPublisher
import com.dynokore.gst.payload.application.payload.RegenerationFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * [PayloadReadyPublisher] over the transactional outbox (Richardson): `PayloadReady` is appended in the open
 * transaction of the caller, so a rollback leaves no event. The payload `docsequence` is the number
 * `appendSequenced` reserves for the same row; this class reads no counter. `payloadBytes` is standard base64 and
 * `aggregateId` is written only when set. The Doc UUID is the aggregate key. `regenerationKey` (additive, API-41,
 * INV-REQ-591) is written only when [PayloadReadyFacts.regeneration] is set; an unacceptable one is refused before
 * anything is appended. The pack reference of the key is the `packType` of the fact.
 */
class OutboxPayloadReadyPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
) : PayloadReadyPublisher {
    override fun stage(facts: PayloadReadyFacts) {
        facts.regeneration?.let(::requireAcceptable)
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        outbox.appendSequenced(
            EVENT_TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(facts.entityRef),
                docUuid = DocUuid(facts.docUuid),
                transactionDate = null,
                aggregateKey = facts.docUuid.toString(),
                aggregateVersion = facts.version.toLong(),
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        ) { docSequence -> payload(facts, docSequence) }
    }

    private fun payload(
        facts: PayloadReadyFacts,
        docSequence: Long,
    ): String =
        buildString {
            append("{\"docUuid\":").append(json(facts.docUuid.toString()))
            append(",\"version\":").append(facts.version)
            append(",\"docsequence\":").append(docSequence)
            append(",\"submissionType\":").append(json(facts.submissionType))
            append(",\"specificationVersion\":").append(json(facts.specificationVersion))
            append(",\"payloadBytes\":").append(json(Base64.getEncoder().encodeToString(facts.payloadBytes)))
            append(",\"payloadSha256\":").append(json(facts.payloadSha256))
            facts.regeneration?.let { append(",\"regenerationKey\":").append(regenerationKey(it)) }
            facts.aggregateId?.let { append(",\"aggregateId\":").append(json(it)) }
            append('}')
        }

    /** The bounds of `regenerationKey` in the published `payload-ready-payload.schema.json`. */
    private fun requireAcceptable(regeneration: RegenerationFacts) {
        require(regeneration.canonicalVersion >= 1) { "regeneration canonicalVersion is below 1" }
        requireText("specificationRelease", regeneration.specificationRelease)
        val packs = regeneration.packVersions
        require(packs.size in 1..MAX_PACKS) { "regeneration needs 1 to $MAX_PACKS pack versions" }
        packs.forEach {
            requireText("packType", it.packType)
            requireText("pack version", it.version)
        }
        require(packs.distinctBy { it.packType }.size == packs.size) { "regeneration names a packType twice" }
    }

    private fun requireText(
        name: String,
        text: String,
    ) = require(text.isNotBlank() && text.length <= MAX_TEXT) {
        "regeneration $name is blank or longer than $MAX_TEXT characters"
    }

    private fun regenerationKey(regeneration: RegenerationFacts): String {
        val packs =
            regeneration.packVersions.joinToString(",") {
                "{\"packRef\":${json(it.packType)},\"version\":${json(it.version)}}"
            }
        return "{\"canonicalVersion\":${regeneration.canonicalVersion}," +
            "\"specificationVersion\":${json(regeneration.specificationRelease)},\"packVersions\":[$packs]}"
    }

    private fun json(text: String): String =
        buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.payload.PayloadReady.v1"
        private const val MAX_PACKS = 20
        private const val MAX_TEXT = 64
    }
}
