package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HandleAccessAlertRaisedTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val at = Instant.parse("2026-11-21T00:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    private class Holders(
        private val home: TenantId,
        private val byRole: Map<String, List<String>>,
    ) : RoleHolders {
        val queries = mutableListOf<Pair<TenantId, String>>()

        override fun holders(
            tenant: TenantId,
            role: String,
        ): List<String> {
            queries += tenant to role
            return if (tenant == home) byRole[role].orEmpty() else listOf("other")
        }
    }

    private class Store : AccessAlertStore {
        val drafts = mutableListOf<AccessAlertDraft>()
        private val open = mutableSetOf<Pair<TenantId, String>>()

        override fun raise(draft: AccessAlertDraft): Boolean {
            if (!open.add(draft.tenant to draft.entityRef)) return false
            drafts += draft
            return true
        }
    }

    private val full =
        Holders(
            t1,
            mapOf(
                "tenant.admin" to listOf("u1", "u2"),
                "security.officer" to listOf("u2", "u3", " "),
                "tax.approver" to listOf("u9"),
            ),
        )

    private fun payload(
        code: Any? = "ACC-001",
        scope: Any? = "entity:E1",
    ) = mapOf("accCode" to code, "scope" to scope)

    private fun use(
        holders: Holders,
        store: Store,
    ) = HandleAccessAlertRaised(holders, store, clock)

    @Test
    fun `INV-AC-27269 one SECURITY ACC-001 draft for E1 to u1, u2, u3 and no tier`() {
        val store = Store()
        use(full, store).handle(t1, payload()) shouldBe AccessAlertResult.RAISED
        store.drafts shouldBe
            listOf(AccessAlertDraft(t1, AlertClass.SECURITY, "ACC-001", "E1", at, listOf("u1", "u2", "u3")))
    }

    @Test
    fun `INV-AC-27269 tax approver holders alone never recipients, only u9 throws NotDelivered, store empty`() {
        val store = Store()
        val only9 = Holders(t1, mapOf("tax.approver" to listOf("u9")))
        shouldThrow<AccessAlertNotDelivered> { use(only9, store).handle(t1, payload()) }
        store.drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27269 accCode ACC-003, acc-001 and ACC-0011 each return IGNORED_OTHER_CODE, nothing stored`() {
        val store = Store()
        for (code in listOf("ACC-003", "acc-001", "ACC-0011", null)) {
            use(full, store).handle(t1, payload(code = code)) shouldBe AccessAlertResult.IGNORED_OTHER_CODE
        }
        store.drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27269 bad scope each throws ContractViolation and stores nothing`() {
        val store = Store()
        val bad = listOf("entity:" + "x".repeat(65), "entity:", "entity:  ", "E1", "Entity:E1", "entity", null, 5)
        for (scope in bad) {
            shouldThrow<ContractViolation> { use(full, store).handle(t1, payload(scope = scope)) }
        }
        store.drafts shouldBe emptyList()
        use(full, store).handle(t1, payload(scope = "entity:" + "x".repeat(64))) shouldBe AccessAlertResult.RAISED
    }

    @Test
    fun `INV-AC-27269 both roles empty throws AccessAlertNotDelivered, store empty`() {
        val store = Store()
        shouldThrow<AccessAlertNotDelivered> { use(Holders(t1, emptyMap()), store).handle(t1, payload()) }
        store.drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27269 a second handling returns ALREADY_OPEN, one draft held`() {
        val store = Store()
        val handler = use(full, store)
        handler.handle(t1, payload()) shouldBe AccessAlertResult.RAISED
        handler.handle(t1, payload()) shouldBe AccessAlertResult.ALREADY_OPEN
        store.drafts.size shouldBe 1
    }

    @Test
    fun `INV-AC-27269 the roles queried are exactly tenant admin and security officer of the event tenant only`() {
        val h = Holders(t1, mapOf("tenant.admin" to listOf("u1")))
        use(h, Store()).handle(t1, payload())
        h.queries shouldBe listOf(t1 to "tenant.admin", t1 to "security.officer")
        h.queries.none { it.first == t2 } shouldBe true
    }
}
