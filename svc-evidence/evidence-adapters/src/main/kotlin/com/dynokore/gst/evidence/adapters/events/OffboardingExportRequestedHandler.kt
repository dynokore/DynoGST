package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.gst.evidence.application.retention.CustodyHolder
import com.dynokore.gst.evidence.application.retention.CustodyHolderRecords
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The consumer of `gst.tenancy.offboarding-export-requested.v1` (INV-REQ-877): builds the [ExportRequest] from the
 * payload and the envelope id and runs [ExportTenantEvidence], which registers the job REQUESTED (idempotent on the
 * offboarding reference) and runs it. It runs in the inbox consumer's transaction, so the job row, the outbox row of
 * the result and the inbox row commit together; an unexpected failure rolls all of them back.
 *
 * A payload that is not JSON, lacks `tenantRef`, `offboardingRef` or `exportRef`, names an unknown `scope`, or whose
 * `tenantRef` differs from the envelope tenant, is refused with [ContractViolation]: a poison record that writes
 * nothing.
 *
 * The optional `retentionCustodyHolder` and `noticeWindowDays` (INV-REQ-639, INV-AC-26713) are stored through
 * [CustodyHolderRecords] in the same transaction, once per offboarding. An absent holder writes no row; a present but
 * malformed holder, or a window that is not an integer of at least 1, is a poison record. No message holds a contact
 * value.
 */
class OffboardingExportRequestedHandler(
    private val useCase: ExportTenantEvidence,
    private val custodyHolders: CustodyHolderRecords,
    private val clock: Clock,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val offboardingRef = required(data, "offboardingRef")
        val exportRef = required(data, "exportRef")
        if (required(data, "tenantRef") != event.tenant.toString()) {
            throw ContractViolation("tenantRef differs from the envelope tenant")
        }
        val request = request(event, data, offboardingRef, exportRef)
        val holder = custodyHolder(data)
        val window = noticeWindowDays(data)
        if (holder != null) {
            custodyHolders.record(event.tenant, offboardingRef, holder, window, clock.instant())
        }
        useCase.execute(request)
        return HandleOutcome.APPLIED
    }

    private fun request(
        event: Envelope,
        data: JsonNode,
        offboardingRef: String,
        exportRef: String,
    ): ExportRequest {
        val scope =
            ExportScope.entries.firstOrNull { it.name == text(data, "scope") }
                ?: throw ContractViolation("scope is missing or unknown")
        return try {
            ExportRequest(event.tenant, offboardingRef, exportRef, scope, text(data, "entityRef"), event.id.value)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("the request is inconsistent: ${e.message}", e)
        }
    }

    private fun custodyHolder(data: JsonNode): CustodyHolder? {
        val node = data.get("retentionCustodyHolder")?.takeUnless { it.isNull } ?: return null
        val mobile = node.get("contactMobile")?.takeUnless { it.isNull }
        val name = if (node.isObject) text(node, "name") else null
        val email = if (node.isObject) text(node, "contactEmail") else null
        val mobileOk = mobile == null || mobile.isString
        if (name == null || email == null || !mobileOk) {
            throw ContractViolation(
                "retentionCustodyHolder is not an object with a name, a contactEmail and a string mobile",
            )
        }
        return CustodyHolder(name, email, mobile?.asString()?.takeIf { it.isNotBlank() })
    }

    private fun noticeWindowDays(data: JsonNode): Int? {
        val node = data.get("noticeWindowDays")?.takeUnless { it.isNull } ?: return null
        if (!node.isInt || node.asInt() < 1) throw ContractViolation("noticeWindowDays is not an integer of at least 1")
        return node.asInt()
    }

    private fun required(
        data: JsonNode,
        name: String,
    ): String = text(data, name) ?: throw ContractViolation("$name is missing or blank")

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun text(
        data: JsonNode,
        name: String,
    ): String? = data.get(name)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
}
