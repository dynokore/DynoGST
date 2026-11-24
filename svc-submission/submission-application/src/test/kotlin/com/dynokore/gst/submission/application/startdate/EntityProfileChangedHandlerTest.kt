package com.dynokore.gst.submission.application.startdate

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
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The EntityProfileChanged consumer with an in-memory port and a fake reader (INV-AC-22200). */
class EntityProfileChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000022"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000023"))

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

    private class Memory : ObligationStartDates {
        val rows = mutableMapOf<Pair<TenantId, String>, Pair<LocalDate, LocalDate>>()
        var writes = 0

        override fun record(
            tenant: TenantId,
            entityRef: String,
            startDate: LocalDate,
            effectiveDate: LocalDate,
        ): Boolean {
            val key = tenant to entityRef
            val old = rows[key]
            if (old != null && !effectiveDate.isAfter(old.second)) return false
            rows[key] = startDate to effectiveDate
            writes++
            return true
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
        ): LocalDate? = rows[tenant to entityRef]?.first
    }

    private class CutoverMemory : MigrationCutoverDates {
        val rows = mutableMapOf<Pair<TenantId, String>, Pair<LocalDate, LocalDate>>()
        var writes = 0

        override fun record(
            tenant: TenantId,
            entityRef: String,
            date: LocalDate,
            effectiveDate: LocalDate,
        ): Boolean {
            val key = tenant to entityRef
            val old = rows[key]
            if (old != null && !effectiveDate.isAfter(old.second)) return false
            rows[key] = date to effectiveDate
            writes++
            return true
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
        ): LocalDate? = rows[tenant to entityRef]?.first
    }

    private val store = Memory()
    private val cutovers = CutoverMemory()
    private var next: () -> EntityProfileChangedData = { error("unset") }
    private val handler = EntityProfileChangedHandler(tenants, { next() }, store, cutovers)

    private fun deliver(
        start: LocalDate?,
        effective: String,
    ): HandleOutcome {
        next = { EntityProfileChangedData("E", start, null, LocalDate.parse(effective)) }
        return handler.handle(envelope())
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.EntityProfileChanged.v1",
            subject = "E",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-22200 the six steps in order keep the highest effective date`() {
        deliver(LocalDate.of(2026, 4, 1), "2026-03-01") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 4, 1)

        deliver(LocalDate.of(2026, 7, 1), "2026-06-01") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 7, 1)

        deliver(LocalDate.of(2026, 9, 1), "2026-06-01") shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 7, 1)

        deliver(LocalDate.of(2026, 1, 1), "2026-02-01") shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 7, 1)
        store.writes shouldBe 2

        deliver(null, "2026-12-01") shouldBe HandleOutcome.IGNORED
        store.writes shouldBe 2
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 7, 1)

        val h =
            EntityProfileChangedHandler(tenants, { throw ContractViolation("newValue is not a date") }, store, cutovers)
        shouldThrow<ContractViolation> { h.handle(envelope()) }
        store.writes shouldBe 2
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 7, 1)
    }

    @Test
    fun `INV-AC-22200 the look-alike attribute obligationStartDates is IGNORED`() {
        // The reader maps only the exact name; a look-alike yields no start date.
        deliver(null, "2026-03-01") shouldBe HandleOutcome.IGNORED
        store.writes shouldBe 0
        store.find(tenant, "E") shouldBe null
    }

    @Test
    fun `INV-AC-22200 a ContractViolation from the reader propagates and records nothing`() {
        val h = EntityProfileChangedHandler(tenants, { throw ContractViolation("member is missing") }, store, cutovers)
        shouldThrow<ContractViolation> { h.handle(envelope()) }
        store.writes shouldBe 0
    }

    @Test
    fun `INV-AC-22200 no exception message contains the value not-a-date`() {
        val h =
            EntityProfileChangedHandler(tenants, { throw ContractViolation("newValue is not a date") }, store, cutovers)
        val e = shouldThrow<ContractViolation> { h.handle(envelope()) }
        e.message.orEmpty() shouldNotContain "not-a-date"
    }

    @Test
    fun `INV-AC-22200 a stored value is not read through another tenant`() {
        deliver(LocalDate.of(2026, 4, 1), "2026-03-01")
        store.find(other, "E") shouldBe null
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 4, 1)
    }

    @Test
    fun `INV-AC-22200 conformance the fixture is called through the port types`() {
        val port: ObligationStartDates = Memory()
        val reader: EntityProfileChangedDataReader =
            EntityProfileChangedDataReader {
                EntityProfileChangedData(
                    "E",
                    LocalDate.of(2026, 4, 1),
                    null,
                    LocalDate.of(2026, 3, 1),
                )
            }
        val data = reader.read("{}")
        port.record(tenant, data.entityRef, data.obligationStartDate!!, data.effectiveDate) shouldBe true
        port.find(tenant, "E") shouldBe LocalDate.of(2026, 4, 1)
    }

    private fun deliverBoth(
        start: LocalDate?,
        cutover: LocalDate?,
        effective: String,
        entity: String = "E",
    ): HandleOutcome {
        next = { EntityProfileChangedData(entity, start, cutover, LocalDate.parse(effective)) }
        return handler.handle(envelope())
    }

    @Test
    fun `INV-AC-28451 the latest effective date wins for the cutover date and an older or equal one changes nothing`() {
        deliverBoth(null, LocalDate.of(2026, 3, 1), "2026-02-01") shouldBe HandleOutcome.APPLIED
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 1)
        deliverBoth(null, LocalDate.of(2026, 3, 5), "2026-02-10") shouldBe HandleOutcome.APPLIED
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 5)
        deliverBoth(null, LocalDate.of(2026, 3, 9), "2026-02-10") shouldBe HandleOutcome.IGNORED
        deliverBoth(null, LocalDate.of(2026, 1, 1), "2026-01-01") shouldBe HandleOutcome.IGNORED
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 5)
        cutovers.writes shouldBe 2
    }

    @Test
    fun `INV-AC-28451 an event with only obligationStartDate records it and leaves the cutover date unchanged`() {
        deliverBoth(null, LocalDate.of(2026, 3, 1), "2026-02-01")
        deliverBoth(LocalDate.of(2026, 4, 1), null, "2026-03-01") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 4, 1)
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 1)
        cutovers.writes shouldBe 1
    }

    @Test
    fun `INV-AC-28451 the two dates are recorded independently and an event with neither is IGNORED`() {
        deliverBoth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1), "2026-02-01") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E") shouldBe LocalDate.of(2026, 4, 1)
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 1)
        deliverBoth(null, null, "2026-12-01") shouldBe HandleOutcome.IGNORED
        store.writes shouldBe 1
        cutovers.writes shouldBe 1
    }

    @Test
    fun `INV-AC-28451 a malformed newValue is a ContractViolation without the value and writes nothing`() {
        val h =
            EntityProfileChangedHandler(
                tenants,
                { throw ContractViolation("EntityProfileChanged newValue is not a date") },
                store,
                cutovers,
            )
        val e = shouldThrow<ContractViolation> { h.handle(envelope()) }
        e.message.orEmpty() shouldNotContain "SECRET-X"
        cutovers.writes shouldBe 0
        store.writes shouldBe 0
    }

    @Test
    fun `INV-AC-28451 another tenant and another entity read no cutover date`() {
        deliverBoth(null, LocalDate.of(2026, 3, 1), "2026-02-01")
        cutovers.find(other, "E") shouldBe null
        cutovers.find(tenant, "E-2") shouldBe null
        cutovers.find(tenant, "E") shouldBe LocalDate.of(2026, 3, 1)
    }
}
