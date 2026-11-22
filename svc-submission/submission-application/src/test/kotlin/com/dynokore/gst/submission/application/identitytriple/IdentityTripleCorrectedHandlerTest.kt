package com.dynokore.gst.submission.application.identitytriple

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The IdentityTripleCorrected consumer with a fake reader and a fake store (INV-AC-26836). */
class IdentityTripleCorrectedHandlerTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000026801"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000026802"))
    private var current = t1

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = current

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class FakeStore(
        private val answer: Boolean,
    ) : IdentityTripleCorrections {
        val calls = mutableListOf<Pair<TenantId, IdentityTripleCorrectedData>>()

        override fun record(
            tenant: TenantId,
            data: IdentityTripleCorrectedData,
        ): Boolean {
            calls += tenant to data
            return answer
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
            constitution: String,
            changeRef: String,
        ): IdentityTripleCorrectedData? = null
    }

    private val data = IdentityTripleCorrectedData("E1", "C1", LocalDate.of(2026, 3, 1), "R1", true)

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/masterdata-svc",
            type = "com.dynokore.gst.masterdata.IdentityTripleCorrected.v1",
            subject = "E1",
            time = Instant.parse("2026-11-21T08:00:00Z"),
            tenant = current,
            entity = EntityRef("E1"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "masterdata-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-26836 case 1 a valid event is APPLIED and the store gets one record call`() {
        val store = FakeStore(true)
        IdentityTripleCorrectedHandler(tenants, { data }, store).handle(envelope()) shouldBe HandleOutcome.APPLIED
        store.calls shouldBe listOf(tenants.current() to data)
    }

    @Test
    fun `INV-AC-26836 case 2 a store that answers false gives IGNORED`() {
        val store = FakeStore(false)
        IdentityTripleCorrectedHandler(tenants, { data }, store).handle(envelope()) shouldBe HandleOutcome.IGNORED
        store.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-26836 case 3 a ContractViolation from the reader propagates and the store gets no call`() {
        val store = FakeStore(true)
        val handler = IdentityTripleCorrectedHandler(tenants, { throw ContractViolation("member is missing") }, store)
        shouldThrow<ContractViolation> { handler.handle(envelope()) }
        store.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26836 case 4 the tenant passed is the tenant of the call`() {
        val store = FakeStore(true)
        val handler = IdentityTripleCorrectedHandler(tenants, { data }, store)
        current = t1
        handler.handle(envelope())
        current = t2
        handler.handle(envelope())
        store.calls shouldBe listOf(t1 to data, t2 to data)
    }
}
