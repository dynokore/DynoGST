package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.OffboardingExportRequestedHandler
import com.dynokore.gst.evidence.application.export.ExportClassSources
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportResult
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.gst.evidence.application.retention.CustodyHolder
import com.dynokore.gst.evidence.application.retention.CustodyHolderRecords
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** INV-AC-22949: the handler builds the request from the payload and the envelope id; poison writes nothing. */
class OffboardingExportRequestedHandlerTest {
    private val tenant = UUID.randomUUID()
    private val registered = mutableListOf<ExportRequest>()
    private val published = mutableListOf<ExportResult>()

    private val jobs =
        object : ExportJobs {
            override fun find(
                tenant: TenantId,
                offboardingRef: String,
            ): ExportJob? = null

            override fun findLatest(
                tenant: TenantId,
                scope: ExportScope,
            ): ExportJob? = null

            override fun register(request: ExportRequest): ExportJob {
                registered += request
                return ExportJob(request.tenant, request.offboardingRef, request.exportRef, ExportState.REQUESTED)
            }

            override fun markReady(
                job: ExportJob,
                manifestRef: String,
                itemCount: Int,
                contents: List<String>,
            ) = job.copy(state = ExportState.READY)

            override fun markFailed(
                job: ExportJob,
                reason: String,
            ) = job.copy(state = ExportState.FAILED, failureReason = reason)
        }

    // no source is bound, so the run ends FAILED naming the classes: the handler only has to hand the request over
    private val stored = mutableListOf<List<Any?>>()
    private val receivedAt = Instant.parse("2026-11-15T08:31:00Z")

    private val custodyHolders =
        object : CustodyHolderRecords {
            override fun record(
                tenant: TenantId,
                offboardingRef: String,
                holder: CustodyHolder,
                noticeWindowDays: Int?,
                receivedAt: Instant,
            ) {
                stored += listOf(tenant, offboardingRef, holder, noticeWindowDays, receivedAt)
            }
        }

    private val handler =
        OffboardingExportRequestedHandler(
            ExportTenantEvidence(
                ExportClassSources(emptyMap()),
                jobs,
                { _, _, _ -> "ref" },
                { true },
                { published += it },
                Clock.systemUTC(),
            ),
            custodyHolders,
            Clock.fixed(receivedAt, java.time.ZoneOffset.UTC),
        )

    private fun envelope(
        data: String,
        id: String = "ev-1",
    ) = Envelope(
        id = EventId(id),
        source = "/gst/tenancy-svc",
        type = "com.dynokore.gst.tenancy.OffboardingExportRequested.v1",
        subject = "OFF-1",
        time = Instant.parse("2026-11-15T08:30:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun payload(
        drop: String? = null,
        tenantRef: String = tenant.toString(),
        scope: String = "TENANT",
    ): String {
        val members =
            linkedMapOf(
                "tenantRef" to tenantRef,
                "offboardingRef" to "OFF-1",
                "exportRef" to "EXP-1",
                "scope" to scope,
                "requestedAt" to "2026-11-15T08:00:00Z",
            )
        drop?.let { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }

    @Test
    fun `INV-AC-22949 a request registers the job with the payload members and the envelope id, and reports APPLIED`() {
        handler.handle(envelope(payload(), "ev-77")) shouldBe HandleOutcome.APPLIED
        val request = registered.single()
        request.tenant shouldBe TenantId(tenant)
        request.offboardingRef shouldBe "OFF-1"
        request.exportRef shouldBe "EXP-1"
        request.scope shouldBe ExportScope.TENANT
        request.requestedEventId shouldBe "ev-77"
        published.single().state shouldBe ExportState.FAILED
    }

    @Test
    fun `INV-AC-22949 a payload without offboardingRef is a contract violation and registers nothing`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "offboardingRef"))) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a payload without tenantRef or exportRef, or with an unknown scope, is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "tenantRef"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "exportRef"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(scope = "ALL"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(drop = "scope"))) }
        shouldThrow<ContractViolation> { handler.handle(envelope("not json")) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a tenantRef that differs from the envelope tenant is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(tenantRef = UUID.randomUUID().toString()))) }
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22949 a scope ENTITY request without entityRef is refused`() {
        shouldThrow<ContractViolation> { handler.handle(envelope(payload(scope = "ENTITY"))) }
        registered shouldBe emptyList()
    }

    private fun withMembers(extra: String) = payload().dropLast(1) + "," + extra + "}"

    @Test
    fun `INV-AC-26713 the holder and the window are stored equal to the event`() {
        handler.handle(
            envelope(
                withMembers(
                    """"retentionCustodyHolder":{"name":"A Holder","contactEmail":"a@example.test",""" +
                        """"contactMobile":"+6590000000"},""" +
                        """"noticeWindowDays":45""",
                ),
            ),
        ) shouldBe HandleOutcome.APPLIED
        val row = stored.single()
        row[0] shouldBe TenantId(tenant)
        row[1] shouldBe "OFF-1"
        row[2] shouldBe CustodyHolder("A Holder", "a@example.test", "+6590000000")
        row[3] shouldBe 45
        row[4] shouldBe receivedAt
        registered.size shouldBe 1
    }

    @Test
    fun `INV-AC-26713 a holder without mobile and without window is stored with nulls`() {
        handler.handle(
            envelope(withMembers(""""retentionCustodyHolder":{"name":"A","contactEmail":"a@example.test"}""")),
        )
        stored.single()[2] shouldBe CustodyHolder("A", "a@example.test", null)
        stored.single()[3] shouldBe null
    }

    @Test
    fun `INV-AC-26713 an event without the members writes no row and the export job still registers`() {
        handler.handle(envelope(payload())) shouldBe HandleOutcome.APPLIED
        stored shouldBe emptyList()
        registered.size shouldBe 1
    }

    @Test
    fun `INV-AC-26713 a tenantRef differing from the envelope tenant writes no row`() {
        val bad =
            payload(tenantRef = UUID.randomUUID().toString()).dropLast(1) +
                ""","retentionCustodyHolder":{"name":"A","contactEmail":"a@example.test"}}"""
        shouldThrow<ContractViolation> { handler.handle(envelope(bad)) }
        stored shouldBe emptyList()
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26713 a malformed holder or window is refused, writes nothing and names no contact value`() {
        val secret = "secret@example.test"
        val malformed =
            listOf(
                """"retentionCustodyHolder":{"name":"A"}""",
                """"retentionCustodyHolder":{"name":"A","contactEmail":" "}""",
                """"retentionCustodyHolder":{"contactEmail":"$secret"}""",
                """"retentionCustodyHolder":"$secret"""",
                """"retentionCustodyHolder":{"name":"A","contactEmail":"$secret","contactMobile":5}""",
                """"retentionCustodyHolder":{"name":"A","contactEmail":"$secret"},"noticeWindowDays":0""",
                """"retentionCustodyHolder":{"name":"A","contactEmail":"$secret"},"noticeWindowDays":1.5""",
                """"retentionCustodyHolder":{"name":"A","contactEmail":"$secret"},"noticeWindowDays":"30"""",
                """"noticeWindowDays":0""",
            )
        malformed.forEach { members ->
            val e = shouldThrow<ContractViolation> { handler.handle(envelope(withMembers(members))) }
            e.message.orEmpty().contains(secret) shouldBe false
        }
        stored shouldBe emptyList()
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26713 the custody holder never prints its contact values`() {
        CustodyHolder("A Holder", "a@example.test", "+6590000000").toString() shouldBe "CustodyHolder(redacted)"
    }
}
