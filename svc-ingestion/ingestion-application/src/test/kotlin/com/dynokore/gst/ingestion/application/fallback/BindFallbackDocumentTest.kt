package com.dynokore.gst.ingestion.application.fallback

import com.dynokore.gst.ingestion.domain.fallback.AfterTheFactCheck
import com.dynokore.gst.ingestion.domain.fallback.FallbackReason
import com.dynokore.gst.ingestion.domain.fallback.IssuanceMode
import com.dynokore.gst.ingestion.domain.fallback.NotFallbackReason
import com.dynokore.gst.ingestion.domain.fallback.RefusalReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-REQ-1321: bind a fallback document once and queue its checks once, over in-memory ports. */
class BindFallbackDocumentTest {
    private class MemoryStore : FallbackBindingStore {
        val bindings = mutableMapOf<Triple<UUID, String, String>, FallbackBinding>()
        val queued = mutableListOf<AfterTheFactCheck>()

        override fun bindOnce(
            binding: FallbackBinding,
            checks: List<AfterTheFactCheck>,
        ): BindStored {
            val key = Triple(binding.tenantId, binding.sourceSystem, binding.sourceKey)
            if (key in bindings) return BindStored.ALREADY_BOUND
            bindings[key] = binding
            queued += checks
            return BindStored.CREATED
        }
    }

    private class Modes(
        private val mode: IssuanceMode?,
    ) : IssuanceModeReader {
        var calls = 0

        override fun modeOn(
            tenantId: UUID,
            entityRef: String,
            date: LocalDate,
        ): IssuanceMode? {
            calls++
            return mode
        }
    }

    private val tenant = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val allChecks =
        listOf(
            AfterTheFactCheck.VALIDATION_LAYER_1,
            AfterTheFactCheck.VALIDATION_LAYER_2,
            AfterTheFactCheck.WRONGFUL_GST_CHARGE,
        )

    private fun command(
        tenantId: UUID = tenant,
        sourceKey: String = "K1",
        network: Boolean = true,
        relay: Boolean = false,
    ) = BindFallbackCommand(tenantId, "ERP", sourceKey, "E1", "2026-Q3", network, relay, LocalDate.of(2026, 9, 1))

    private fun binding(
        reason: FallbackReason,
        tenantId: UUID = tenant,
        sourceKey: String = "K1",
    ) = FallbackBinding(tenantId, "ERP", sourceKey, "E1", "2026-Q3", reason)

    @Test
    @DisplayName("INV-AC-26635 B1 mode RELAY binds once and queues the three checks in order")
    fun b1() {
        val store = MemoryStore()
        BindFallbackDocument(Modes(IssuanceMode.RELAY), store).execute(command()) shouldBe
            BindOutcome.Bound(binding(FallbackReason.MODE_RELAY), allChecks)
        store.bindings.size shouldBe 1
        store.queued shouldBe allChecks
    }

    @Test
    @DisplayName("INV-AC-26635 B2 the same command again is already bound and queues nothing")
    fun b2() {
        val store = MemoryStore()
        val use = BindFallbackDocument(Modes(IssuanceMode.RELAY), store)
        use.execute(command())
        use.execute(command()) shouldBe BindOutcome.AlreadyBound(binding(FallbackReason.MODE_RELAY))
        store.bindings.size shouldBe 1
        store.queued.size shouldBe 3
    }

    @Test
    @DisplayName("INV-AC-26635 B3 unknown mode binds as a fallback with MODE_UNKNOWN")
    fun b3() {
        val store = MemoryStore()
        BindFallbackDocument(Modes(null), store).execute(command()) shouldBe
            BindOutcome.Bound(binding(FallbackReason.MODE_UNKNOWN), allChecks)
        store.bindings.size shouldBe 1
        store.queued shouldBe allChecks
    }

    @Test
    @DisplayName("INV-AC-26635 B4 mode OBSERVE and mode NONE are not a fallback")
    fun b4() {
        for (mode in listOf(IssuanceMode.OBSERVE, IssuanceMode.NONE)) {
            val store = MemoryStore()
            BindFallbackDocument(Modes(mode), store).execute(command()) shouldBe
                BindOutcome.NotFallback(NotFallbackReason.MODE_NOT_RELAY)
            store.bindings.isEmpty() shouldBe true
            store.queued.isEmpty() shouldBe true
        }
    }

    @Test
    @DisplayName("INV-AC-26635 B5 a relay posting is not a fallback and the mode is not read")
    fun b5() {
        val store = MemoryStore()
        val modes = Modes(IssuanceMode.RELAY)
        BindFallbackDocument(modes, store).execute(command(network = false, relay = true)) shouldBe
            BindOutcome.NotFallback(NotFallbackReason.RELAY_PATH)
        modes.calls shouldBe 0
        store.bindings.isEmpty() shouldBe true
        store.queued.isEmpty() shouldBe true
    }

    @Test
    @DisplayName("INV-AC-26635 B6 no network issuance is not a fallback and the mode is not read")
    fun b6() {
        val store = MemoryStore()
        val modes = Modes(IssuanceMode.RELAY)
        BindFallbackDocument(modes, store).execute(command(network = false, relay = false)) shouldBe
            BindOutcome.NotFallback(NotFallbackReason.NO_NETWORK_ISSUANCE)
        modes.calls shouldBe 0
        store.bindings.isEmpty() shouldBe true
        store.queued.isEmpty() shouldBe true
    }

    @Test
    @DisplayName("INV-AC-26635 B7 contradictory markers are refused with nothing stored")
    fun b7() {
        val store = MemoryStore()
        BindFallbackDocument(Modes(IssuanceMode.RELAY), store).execute(command(network = true, relay = true)) shouldBe
            BindOutcome.Refused(RefusalReason.CONTRADICTORY_MARKERS)
        store.bindings.isEmpty() shouldBe true
        store.queued.isEmpty() shouldBe true
    }

    @Test
    @DisplayName("INV-AC-26635 B8 the identity is the whole triple: another tenant or another key binds again")
    fun b8() {
        val store = MemoryStore()
        val use = BindFallbackDocument(Modes(IssuanceMode.RELAY), store)
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        use.execute(command())
        use.execute(command(tenantId = other)) shouldBe
            BindOutcome.Bound(binding(FallbackReason.MODE_RELAY, tenantId = other), allChecks)
        use.execute(command(sourceKey = "K2")) shouldBe
            BindOutcome.Bound(binding(FallbackReason.MODE_RELAY, sourceKey = "K2"), allChecks)
        store.bindings.size shouldBe 3
        store.queued.size shouldBe 9
    }
}
