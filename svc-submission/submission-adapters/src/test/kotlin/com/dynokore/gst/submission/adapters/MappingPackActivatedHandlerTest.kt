package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.MappingPackActivatedHandler
import com.dynokore.gst.submission.application.compliance.PackActivation
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
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The `MappingPackActivated` handler over a recording port (INV-AC-26739). */
class MappingPackActivatedHandlerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val stored = mutableListOf<Pair<TenantId, PackActivation>>()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String = SignedBindings.ACTOR

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = throw UnsupportedOperationException()
        }

    private val handler =
        MappingPackActivatedHandler(tenants) { t, activation ->
            val fresh = stored.none { it.first == t && it.second.eventId == activation.eventId }
            if (fresh) stored += t to activation
            fresh
        }

    private val members =
        linkedMapOf(
            "entityRef" to "\"E-001\"",
            "packId" to "\"MP-7\"",
            "packVersion" to "\"2026.2\"",
            "effectiveFrom" to "\"2026-11-01\"",
            "effectiveTo" to "\"2027-03-31\"",
            "activatedBy" to "\"user-41\"",
            "activatedAt" to "\"2026-10-30T17:15:00+08:00\"",
        )

    private fun json(values: Map<String, String>) =
        values.entries.joinToString(",", "{", "}") {
            "\"${it.key}\":${it.value}"
        }

    private fun event(
        data: String,
        envelopeTenant: TenantId = tenant,
        id: String = "evt-1",
    ) = Envelope(
        id = EventId(id),
        source = "/gst/rules-svc",
        type = MappingPackActivatedHandler.EVENT_TYPE,
        subject = "MP-7",
        time = Instant.parse("2026-10-30T09:15:01Z"),
        tenant = envelopeTenant,
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "rules-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    @Test
    fun `INV-AC-26739 the port receives the values of the event and a second delivery is ignored`() {
        handler.handle(event(json(members))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(json(members))) shouldBe HandleOutcome.IGNORED
        stored shouldBe
            listOf(
                tenant to
                    PackActivation(
                        "evt-1",
                        "E-001",
                        "MP-7",
                        "2026.2",
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2027, 3, 31),
                        "user-41",
                        Instant.parse("2026-10-30T09:15:00Z"),
                    ),
            )
    }

    @Test
    fun `INV-AC-26739 an open-ended activation has no end date and unknown members are ignored`() {
        handler.handle(event(json(members - "effectiveTo" + ("added" to "{\"x\":1}")))) shouldBe HandleOutcome.APPLIED
        stored.single().second.effectiveTo shouldBe null
    }

    @Test
    fun `INV-AC-26739 a payload that is not a JSON object is refused and stores nothing`() {
        for (data in listOf("", "not json", "{", "[]", "null", "\"text\"", "${json(members)} trailing")) {
            withClue(data) { shouldThrow<ContractViolation> { handler.handle(event(data)) } }
        }
        stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26739 a missing required member is refused by name and stores nothing`() {
        for (name in members.keys - "effectiveTo") {
            withClue(name) {
                val refusal = shouldThrow<ContractViolation> { handler.handle(event(json(members - name))) }
                refusal.message!!.contains(name) shouldBe true
            }
        }
        // a look-alike member does not stand in for the required one
        val lookAlike = json(members - "packVersion" + ("packLabel" to "\"2026.2\""))
        shouldThrow<ContractViolation> { handler.handle(event(lookAlike)) }
        stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26739 a member of the wrong type or blank is refused and stores nothing`() {
        for (name in members.keys) {
            for (bad in listOf("null", "7", "true", "[\"a\"]", "{}", "\"\"", "\"  \"")) {
                withClue("$name=$bad") {
                    shouldThrow<ContractViolation> { handler.handle(event(json(members + (name to bad)))) }
                }
            }
        }
        stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26739 a date or date-time that is none is refused without echoing the value`() {
        val dates = listOf("2026-02-30", "2026-13-01", "01/11/2026", "2026-11-01T00:00:00Z", "+12026-11-01", "soon")
        for (name in listOf("effectiveFrom", "effectiveTo")) {
            for (bad in dates) {
                withClue("$name=$bad") {
                    val refusal =
                        shouldThrow<ContractViolation> { handler.handle(event(json(members + (name to "\"$bad\"")))) }
                    refusal.message!! shouldNotContain bad
                }
            }
        }
        for (bad in listOf("2026-10-30", "2026-10-30T09:15:00", "yesterday")) {
            withClue(bad) {
                shouldThrow<ContractViolation> {
                    handler.handle(event(json(members + ("activatedAt" to "\"$bad\""))))
                }
            }
        }
        stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26739 an envelope tenant that differs from the bound tenant stores nothing`() {
        shouldThrow<ContractViolation> { handler.handle(event(json(members), TenantId(UUID.randomUUID()))) }
        stored shouldBe emptyList()
    }
}
