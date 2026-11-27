package com.dynokore.gst.aggregation.adapters.events

import com.dynokore.gst.aggregation.application.MissedPart
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/** INV-AC-27726: the MISSED events that [WindowChangedOutbox] appends (INV-REQ-413). */
class MissedWindowOutboxTest {
    private val mapper = ObjectMapper()
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<Pair<OutboundEvent, EventContext>>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                events.forEach { appended += it to context }
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val counters = mutableMapOf<List<Any>, Long>()
    private val versions =
        WindowStateVersions { t, entity, period, from, to ->
            counters.merge(listOf(t, entity, period, from, to), 1L, Long::plus)!!
        }
    private val publisher = WindowChangedOutbox(outbox, tenants, versions)

    private val windowA =
        OpenWindow(
            "E-001",
            "BU-01",
            AggregateType.PCP,
            AggregationWindow(LocalDate.of(2027, 3, 29), LocalDate.of(2027, 4, 4)),
        )
    private val partA = WindowPart(LocalDate.of(2027, 3, 29), LocalDate.of(2027, 3, 31), "2027-03")
    private val a = MissedPart(windowA, partA)
    private val w1 =
        MissedPart(
            OpenWindow(
                "E-002",
                "BU-01",
                AggregateType.PCP,
                AggregationWindow(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28)),
            ),
            null,
        )

    private fun bodies(): List<JsonNode> = appended.map { mapper.readTree(it.first.dataJson) }

    private fun members(body: JsonNode) = body.propertyNames().toList().sorted()

    @Test
    fun `INV-AC-27726 one MISSED event for a part and one with spanKnown false for a window with no span`() {
        // the last event of A had stateVersion 1
        publisher.changed(WindowChange(windowA, partA, WindowState.LATE, null))
        appended.clear()

        publisher.missed(a)
        publisher.missed(w1)

        appended shouldHaveSize 2
        val (known, unknown) = bodies()
        members(known) shouldBe
            listOf("entityRef", "findingCode", "prescribedPeriod", "state", "stateVersion", "window")
        known["entityRef"].asString() shouldBe "E-001"
        known["prescribedPeriod"].asString() shouldBe "2027-03"
        members(known["window"]) shouldBe listOf("from", "to")
        known["window"]["from"].asString() shouldBe "2027-03-29"
        known["window"]["to"].asString() shouldBe "2027-03-31"
        known["state"].asString() shouldBe "MISSED"
        known["findingCode"].asString() shouldBe "ING-027"
        known["stateVersion"].asLong() shouldBe 2L

        members(unknown) shouldBe
            listOf("entityRef", "findingCode", "prescribedPeriod", "spanKnown", "state", "stateVersion", "window")
        unknown["entityRef"].asString() shouldBe "E-002"
        unknown["prescribedPeriod"].asString() shouldBe "UNKNOWN-SPAN"
        members(unknown["window"]) shouldBe listOf("from", "to")
        unknown["window"]["from"].asString() shouldBe "2027-02-01"
        unknown["window"]["to"].asString() shouldBe "2027-02-28"
        unknown["state"].asString() shouldBe "MISSED"
        unknown["findingCode"].asString() shouldBe "ING-027"
        unknown["spanKnown"].isBoolean shouldBe true
        unknown["spanKnown"].asBoolean() shouldBe false
        unknown["stateVersion"].asLong() shouldBe 1L

        counters.keys shouldBe
            setOf(
                listOf(tenant, "E-001", "2027-03", partA.start, partA.end),
                listOf(tenant, "E-002", "UNKNOWN-SPAN", w1.window.window.start, w1.window.window.end),
            )
        appended.map { it.first.type }.toSet() shouldBe setOf(WindowChangedOutbox.TYPE)
        appended.map { it.second.tenant }.toSet() shouldBe setOf(tenant)
        appended.map { it.second.entity.value } shouldBe listOf("E-001", "E-002")
        appended.map { it.second.aggregateKey } shouldBe
            listOf("window:E-001:PCP:2027-03-29", "window:E-002:PCP:2027-02-01")
    }

    @Test
    fun `INV-AC-27726 a repeated call writes one more event with a higher stateVersion for each`() {
        listOf(a, w1, a, w1).forEach(publisher::missed)

        bodies().map { it["prescribedPeriod"].asString() to it["stateVersion"].asLong() } shouldBe
            listOf("2027-03" to 1L, "UNKNOWN-SPAN" to 1L, "2027-03" to 2L, "UNKNOWN-SPAN" to 2L)
    }

    @Test
    fun `INV-AC-27726 the CLOSED event of a missed part carries the aggregate id and the next stateVersion`() {
        val aggregateId = UUID.randomUUID()
        publisher.missed(a)
        publisher.missed(a)

        publisher.changed(WindowChange(windowA, partA, WindowState.CLOSED, aggregateId))

        val closed = bodies().last()
        members(closed) shouldBe
            listOf("aggregateId", "entityRef", "prescribedPeriod", "state", "stateVersion", "window")
        closed["state"].asString() shouldBe "CLOSED"
        closed["aggregateId"].asString() shouldBe aggregateId.toString()
        closed["stateVersion"].asLong() shouldBe 3L
        appended.map { it.second.aggregateKey }.toSet() shouldBe setOf("window:E-001:PCP:2027-03-29")
    }

    @Test
    fun `INV-AC-27726 changed still refuses the state OPEN`() {
        shouldThrow<IllegalArgumentException> {
            publisher.changed(WindowChange(windowA, partA, WindowState.OPEN, null))
        }
        appended.shouldBeEmpty()
        counters.keys.shouldBeEmpty()
    }
}
