package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.Envelope as OutboxEnvelope

/** The channel OffboardingExportRequested: the payload of a published event validates against the pinned schema. */
class OffboardingExportRequestedPayloadSchemaTest {
    private val schema = SchemaFixture.schema("offboarding-export-requested-payload.schema.json")
    private val mapper = JsonMapper.builder().build()
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val recording = RecordingOutbox()
    private val contexts = ArrayList<EventContext>()
    private val capturing =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<OutboxEnvelope> {
                contexts += context
                return recording.append(events, context)
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String = "svc:tenancy-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC)
    private val outbox = OffboardingExportRequestedOutbox(capturing, mapper, tenants, clock)

    @Test
    fun `INV-AC-22960 the payload of a tenant-scope request validates and has no entityRef`() {
        val manifest = outbox.produce(ExportOrder("OFF-1", "T-1", OffboardingScope.TENANT, null, HOLDER, null))
        manifest.exportRef shouldBe "EXP-OFF-1"
        manifest.state shouldBe ExportState.REQUESTED
        manifest.contents.shouldBeEmpty()
        val event = recording.events.single()
        event.type shouldBe "com.dynokore.gst.tenancy.OffboardingExportRequested.v1"
        SchemaFixture.errors(schema, event.dataJson).shouldBeEmpty()
        mapper.readTree(event.dataJson).has("entityRef") shouldBe false
    }

    @Test
    fun `INV-AC-22960 the entity of a tenant-scope request is tenant-wide, never the tenant ref`() {
        outbox.produce(ExportOrder("OFF-4", "T-1", OffboardingScope.TENANT, null, HOLDER, null))
        val context = contexts.single()
        context.entity shouldBe EntityRef.TENANT_WIDE
        context.entity.toString() shouldBe "00000000-0000-0000-0000-000000000000"
        context.entity shouldNotBe EntityRef("T-1")
        context.tenant shouldBe tenant
        context.aggregateKey shouldBe "OFF-4"
    }

    @Test
    fun `INV-AC-22960 the entity of an entity-scope request is the order entity, not tenant-wide`() {
        outbox.produce(ExportOrder("OFF-5", "T-1", OffboardingScope.ENTITY, "ENT-1", HOLDER, null))
        val context = contexts.single()
        context.entity shouldBe EntityRef("ENT-1")
        context.entity shouldNotBe EntityRef.TENANT_WIDE
        context.entity shouldNotBe EntityRef("T-1")
    }

    @Test
    fun `INV-AC-22960 the payload of an entity-scope request validates and names the entity`() {
        outbox.produce(ExportOrder("OFF-2", "T-1", OffboardingScope.ENTITY, "ENT-1", HOLDER, null))
        val json = recording.events.single().dataJson
        SchemaFixture.errors(schema, json).shouldBeEmpty()
        mapper.readTree(json).get("entityRef").asString() shouldBe "ENT-1"
        mapper.readTree(json).get("scope").asString() shouldBe "ENTITY"
    }

    @Test
    fun `INV-AC-22960 a payload without offboardingRef or with an unknown scope is invalid`() {
        val at = "2026-11-20T10:00:00Z"
        val missing = """{"tenantRef":"T","exportRef":"E","scope":"TENANT","requestedAt":"$at"}"""
        val unknown = """{"tenantRef":"T","offboardingRef":"O","exportRef":"E","scope":"ALL","requestedAt":"$at"}"""
        SchemaFixture.errors(schema, missing).shouldNotBeEmpty()
        SchemaFixture.errors(schema, unknown).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-22960 an append that throws is refused as unavailable tenant-export`() {
        val failing =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<OutboxEnvelope> = error("outbox down")
            }
        val thrown =
            shouldThrow<UnavailableInput> {
                OffboardingExportRequestedOutbox(failing, mapper, tenants, clock)
                    .produce(ExportOrder("OFF-3", "T-1", OffboardingScope.TENANT, null, HOLDER, null))
            }
        thrown.input shouldBe "tenant-export"
    }

    @Test
    fun `INV-AC-26712 the payload holds the custody holder and the notice window and validates`() {
        outbox.produce(ExportOrder("OFF-6", "T-1", OffboardingScope.TENANT, null, FULL_HOLDER, 45))
        val json = recording.events.single().dataJson
        SchemaFixture.errors(schema, json).shouldBeEmpty()
        val node = mapper.readTree(json)
        node.get("retentionCustodyHolder").get("name").asString() shouldBe FULL_HOLDER.name
        node.get("retentionCustodyHolder").get("contactEmail").asString() shouldBe FULL_HOLDER.contactEmail
        node.get("retentionCustodyHolder").get("contactMobile").asString() shouldBe FULL_HOLDER.contactMobile
        node.get("noticeWindowDays").asInt() shouldBe 45
        listOf(
            "tenantRef",
            "offboardingRef",
            "exportRef",
            "scope",
            "requestedAt",
        ).forEach { node.has(it) shouldBe true }
    }

    @Test
    fun `INV-AC-26712 noticeWindowDays is absent when the request has none`() {
        outbox.produce(ExportOrder("OFF-7", "T-1", OffboardingScope.TENANT, null, FULL_HOLDER, null))
        val json = recording.events.single().dataJson
        SchemaFixture.errors(schema, json).shouldBeEmpty()
        mapper.readTree(json).has("noticeWindowDays") shouldBe false
    }

    @Test
    fun `INV-AC-26712 contactMobile is absent when null`() {
        outbox.produce(ExportOrder("OFF-8", "T-1", OffboardingScope.TENANT, null, HOLDER, null))
        val json = recording.events.single().dataJson
        SchemaFixture.errors(schema, json).shouldBeEmpty()
        mapper.readTree(json).get("retentionCustodyHolder").has("contactMobile") shouldBe false
    }

    @Test
    fun `INV-AC-26712 the payload never holds a role name`() {
        outbox.produce(ExportOrder("OFF-9", "T-1", OffboardingScope.TENANT, null, FULL_HOLDER, 30))
        val json = recording.events.single().dataJson
        listOf("platform.engineer", "security.officer", "deployment.operator", "tax.approver", "role").forEach {
            json.contains(it) shouldBe false
        }
    }

    @Test
    fun `INV-AC-26712 a payload without the custody holder is not produced`() {
        outbox.produce(ExportOrder("OFF-10", "T-1", OffboardingScope.TENANT, null, HOLDER, null))
        val json = recording.events.single().dataJson
        mapper
            .readTree(json)
            .get("retentionCustodyHolder")
            .get("name")
            .asString() shouldBe HOLDER.name
        val without =
            """{"tenantRef":"T","offboardingRef":"O","exportRef":"E","scope":"TENANT",""" +
                """"requestedAt":"2026-11-20T10:00:00Z","retentionCustodyHolder":{"contactEmail":"a@b.invalid"}}"""
        SchemaFixture.errors(schema, without).shouldNotBeEmpty()
    }

    private companion object {
        val HOLDER = RetentionCustodyHolder("Holder", "holder@example.invalid")
        val FULL_HOLDER = RetentionCustodyHolder("Holder", "holder@example.invalid", "+6591234567")
    }
}
