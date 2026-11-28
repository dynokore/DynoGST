package com.dynokore.gst.submission.application.completeness

import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The MISSED window part in the AggregateWindowChanged consumer over in-memory stores (INV-AC-27723). */
class MissedWindowHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000021"))
    private val from = LocalDate.of(2026, 5, 1)
    private val to = LocalDate.of(2026, 5, 7)
    private val memory = InMemoryCompleteness()

    private fun deliver(data: AggregateWindowChangedData): HandleOutcome =
        AggregateWindowChangedHandler(fixedTenants(tenant), { data }, memory).handle(testEnvelope(tenant))

    @Test
    fun `INV-AC-27723 the stored and event states are exactly these and the constants are the ruled texts`() {
        WindowChangeState.entries.map { it.name } shouldBe listOf("OPEN", "CLOSED", "LATE", "MISSED")
        StoredState.entries.map { it.name } shouldBe listOf("FAILING", "NOT_FAILING", "OPEN", "MISSED")
        WINDOW_FINDING_CODE shouldBe "ING-027"
        SPAN_UNKNOWN_PERIOD shouldBe "UNKNOWN-SPAN"
    }

    @Test
    fun `INV-AC-27723 a MISSED event is recorded as MISSED under its period with its stateVersion`() {
        deliver(
            AggregateWindowChangedData("E1", "P1", from, to, WindowChangeState.MISSED, 3, WindowIdentity.NONE),
        ) shouldBe
            HandleOutcome.APPLIED
        memory.windowRows.keys.single() shouldBe listOf(tenant, "E1", "P1", from, to)
        memory.windowRows.values.single() shouldBe
            InMemoryCompleteness.Stored(StoredRow("window:P1", StoredState.MISSED), 3)
        deliver(
            AggregateWindowChangedData("E1", "P1", from, to, WindowChangeState.MISSED, 3, WindowIdentity.NONE),
        ) shouldBe
            HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-27723 case 9 a MISSED event of unknown span is recorded under UNKNOWN-SPAN`() {
        val event =
            AggregateWindowChangedData(
                "E1",
                SPAN_UNKNOWN_PERIOD,
                from,
                to,
                WindowChangeState.MISSED,
                1,
                WindowIdentity.NONE,
                spanKnown = false,
            )
        deliver(event) shouldBe HandleOutcome.APPLIED
        memory.windowRows.keys.single() shouldBe listOf(tenant, "E1", "UNKNOWN-SPAN", from, to)
        memory.windowRows.values
            .single()
            .row.state shouldBe StoredState.MISSED
    }
}
