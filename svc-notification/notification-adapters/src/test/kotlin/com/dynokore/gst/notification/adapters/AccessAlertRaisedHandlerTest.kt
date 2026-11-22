package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.events.AccessAlertRaisedHandler
import com.dynokore.gst.notification.application.alerting.AccessAlertDraft
import com.dynokore.gst.notification.application.alerting.AccessAlertNotDelivered
import com.dynokore.gst.notification.application.alerting.AccessAlertStore
import com.dynokore.gst.notification.application.alerting.HandleAccessAlertRaised
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

/** INV-REQ-895: the handler consumes AccessAlertRaised under its own type only (INV-AC-27270). */
class AccessAlertRaisedHandlerTest {
    private val type = AccessAlertRaisedHandler.EVENT_TYPE
    private val tenant = TenantId(UUID.randomUUID())
    private val at = Instant.parse("2026-11-16T01:02:03Z")

    private class MemoryStore : AccessAlertStore {
        val open = mutableSetOf<String>()

        override fun raise(draft: AccessAlertDraft) = open.add(draft.entityRef)
    }

    private fun handler(holders: RoleHolders = RoleHolders { _, _ -> listOf("u1") }) =
        AccessAlertRaisedHandler(
            HandleAccessAlertRaised(holders, MemoryStore(), Clock.fixed(at, ZoneOffset.UTC)),
            ObjectMapper(),
        )

    private fun envelope(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/access-svc",
        type = type,
        subject = "e-1",
        time = at,
        tenant = tenant,
        entity = EntityRef("e-1"),
        docUuid = DocUuid(UUID.randomUUID()),
        transactionDate = LocalDate.of(2026, 11, 16),
        docSequence = 1,
        instanceId = "access-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun payload(
        code: String = "ACC-001",
        scope: String = "entity:e-1",
    ) = """{"accCode":"$code","scope":"$scope"}"""

    @Test
    fun `INV-AC-27270 the handler accepts com dynokore gst access AccessAlertRaised v1`() {
        AccessAlertRaisedHandler.EVENT_TYPE shouldBe "com.dynokore.gst.access.AccessAlertRaised.v1"
        AccessAlertRaisedHandler.TOPIC shouldBe "gst.access.access-alert-raised.v1"
        val h = handler()
        h.handle(envelope(type, payload())) shouldBe HandleOutcome.APPLIED
        h.handle(envelope(type, payload())) shouldBe HandleOutcome.IGNORED
        h.handle(envelope(type, payload(code = "ACC-003"))) shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-27270 the types v2, another service segment and another case each throw a contract violation`() {
        listOf(
            "com.dynokore.gst.access.AccessAlertRaised.v2",
            "com.dynokore.gst.submission.AccessAlertRaised.v1",
            "com.dynokore.gst.access.accessalertraised.v1",
        ).forEach { type ->
            shouldThrow<ContractViolation> { handler().handle(envelope(type, payload())) }
        }
    }

    @Test
    fun `INV-AC-27270 a refused payload is a contract violation and a missing recipient is not caught`() {
        shouldThrow<ContractViolation> { handler().handle(envelope(type, payload(scope = "tenant:t"))) }
        shouldThrow<ContractViolation> { handler().handle(envelope(type, "[1]")) }
        shouldThrow<AccessAlertNotDelivered> {
            handler(RoleHolders { _, _ -> emptyList() }).handle(envelope(type, payload()))
        }
    }
}
