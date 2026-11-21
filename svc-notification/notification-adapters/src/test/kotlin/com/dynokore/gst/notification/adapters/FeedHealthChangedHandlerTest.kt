package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.events.FeedHealthChangedHandler
import com.dynokore.gst.notification.application.alerting.AlertId
import com.dynokore.gst.notification.application.alerting.FeedAlertDraft
import com.dynokore.gst.notification.application.alerting.FeedAlertStore
import com.dynokore.gst.notification.application.alerting.FeedHealthState
import com.dynokore.gst.notification.application.alerting.FeedHealthStates
import com.dynokore.gst.notification.application.alerting.HandleFeedHealthChanged
import com.dynokore.gst.notification.application.alerting.ResolveDefaultRecipients
import com.dynokore.gst.notification.application.alerting.RoleHolders
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-REQ-910, slice 2: the handler consumes FeedHealthChanged under its own type only (INV-AC-26516). */
class FeedHealthChangedHandlerTest {
    private val type = FeedHealthChangedHandler.EVENT_TYPE
    private val tenant = TenantId(UUID.randomUUID())
    private val at = Instant.parse("2026-11-15T01:02:03Z")

    private class MemoryStates : FeedHealthStates {
        val rows = mutableMapOf<List<String>, FeedHealthState>()

        override fun find(
            tenant: TenantId,
            feed: String,
            entityRef: String,
            period: String,
            control: String,
        ) = rows[listOf(feed, entityRef, period, control)]

        override fun save(state: FeedHealthState) {
            rows[listOf(state.feed, state.entityRef, state.period, state.control)] = state
        }
    }

    private class MemoryAlerts : FeedAlertStore {
        override fun raise(draft: FeedAlertDraft) = AlertId(UUID.randomUUID().toString())

        override fun resolveIfNoFeedFailed(
            tenant: TenantId,
            entityRef: String,
            period: String,
            control: String,
            at: Instant,
        ) = true
    }

    private val handler =
        FeedHealthChangedHandler(
            HandleFeedHealthChanged(
                MemoryStates(),
                MemoryAlerts(),
                ResolveDefaultRecipients(RoleHolders { _, _ -> listOf("u1") }),
                Clock.fixed(at, ZoneOffset.UTC),
            ),
            ObjectMapper(),
        )

    private fun envelope(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/ingestion-svc",
        type = type,
        subject = "e-1",
        time = at,
        tenant = tenant,
        entity = EntityRef("e-1"),
        docUuid = DocUuid(UUID.randomUUID()),
        transactionDate = LocalDate.of(2026, 11, 15),
        docSequence = 1,
        instanceId = "ingestion-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun payload(
        state: String,
        version: Int?,
    ) = """{"feed":"f","entityRef":"e-1","period":"2026-10","control":"SEQUENCE","state":"$state"""" +
        version?.let { ""","stateVersion":$it""" }.orEmpty() + "}"

    @Test
    fun `INV-AC-26516 the handler accepts com dynokore gst ingestion FeedHealthChanged v1`() {
        FeedHealthChangedHandler.EVENT_TYPE shouldBe "com.dynokore.gst.ingestion.FeedHealthChanged.v1"
        FeedHealthChangedHandler.TOPIC shouldBe "gst.ingestion.feed-health-changed.v1"
        handler.handle(envelope(type, payload("FAILED", 2))) shouldBe HandleOutcome.APPLIED
        handler.handle(envelope(type, payload("CLEARED", 3))) shouldBe HandleOutcome.APPLIED
        handler.handle(envelope(type, payload("FAILED", 1))) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-26516 the types v2, another service segment and another case each throw a contract violation`() {
        listOf(
            "com.dynokore.gst.ingestion.FeedHealthChanged.v2",
            "com.dynokore.gst.submission.FeedHealthChanged.v1",
            "com.dynokore.gst.ingestion.feedhealthchanged.v1",
        ).forEach { type ->
            shouldThrow<ContractViolation> { handler.handle(envelope(type, payload("FAILED", 1))) }
        }
    }

    @Test
    fun `INV-AC-26516 a payload the use case refuses and a payload that is no object are contract violations`() {
        shouldThrow<ContractViolation> {
            handler.handle(envelope(type, payload("BROKEN", 1)))
        }
        shouldThrow<ContractViolation> { handler.handle(envelope(type, "[1]")) }
    }
}
