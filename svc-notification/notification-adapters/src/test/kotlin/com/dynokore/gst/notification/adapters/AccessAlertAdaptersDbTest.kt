package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.persistence.JooqAccessAlerts
import com.dynokore.gst.notification.application.alerting.AccessAlertDraft
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** INV-REQ-895: the jOOQ store of the ACC-001 SECURITY alert and its recipients (INV-AC-27270). */
class AccessAlertAdaptersDbTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val raisedAt = Instant.parse("2026-11-16T01:02:03Z")

    /** One bound runtime connection is the ambient transaction of the store; it is rolled back at the end. */
    private fun <T> bound(
        tenant: UUID,
        block: (Connection, JooqAccessAlerts) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(c, JooqAccessAlerts(DSL.using(c, SQLDialect.POSTGRES)))
            } finally {
                c.rollback()
            }
        }

    private fun entity() = "e-" + UUID.randomUUID()

    private fun draft(
        tenant: UUID,
        entity: String,
        recipients: List<String> = listOf("u1", "u2"),
        cls: AlertClass = AlertClass.SECURITY,
    ) = AccessAlertDraft(TenantId(tenant), cls, "ACC-001", entity, raisedAt, recipients)

    private fun strings(
        c: Connection,
        sql: String,
    ): List<String?> =
        c.createStatement().use { s ->
            s.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
        }

    private fun alertRows(
        c: Connection,
        entity: String,
    ) = strings(
        c,
        "select alert_class || '/' || state || '/' || \"trigger\" || '/' || coalesce(period_ref, '-') " +
            "from notification.alert where entity_ref = '$entity'",
    )

    private fun recipientRows(
        c: Connection,
        entity: String,
    ) = strings(
        c,
        "select r.user_id from notification.alert_recipient r join notification.alert x " +
            "on x.tenant_id = r.tenant_id and x.alert_id = r.alert_id where x.entity_ref = '$entity' order by 1",
    )

    @Test
    fun `INV-AC-27270 a raise inserts one SECURITY row and one recipient row per recipient`() {
        val e = entity()
        bound(a) { c, store ->
            store.raise(draft(a, e)) shouldBe true
            alertRows(c, e) shouldBe listOf("SECURITY/RAISED/ACC-001/-")
            recipientRows(c, e) shouldBe listOf("u1", "u2")
        }
    }

    @Test
    fun `INV-AC-27270 a second raise for the same key inserts no alert row and no recipient row`() {
        val e = entity()
        bound(a) { c, store ->
            store.raise(draft(a, e)) shouldBe true
            store.raise(draft(a, e, listOf("u3"))) shouldBe false
            store.raise(draft(a, e, listOf("u1"))) shouldBe false
            alertRows(c, e).size shouldBe 1
            recipientRows(c, e) shouldBe listOf("u1", "u2")
        }
    }

    /** Commits one T1 alert as the table owner so that other bindings can be tried against it. */
    private fun seedCommitted(entity: String) =
        NotificationDb.superuser().use { su ->
            su.autoCommit = false
            SignedBindings.bind(su, a)
            JooqAccessAlerts(DSL.using(su, SQLDialect.POSTGRES)).raise(draft(a, entity)) shouldBe true
            su.commit()
        }

    private fun purge(entity: String) =
        NotificationDb.superuser().use {
            it.createStatement().use { s ->
                s.execute(
                    "delete from notification.alert_recipient where alert_id in " +
                        "(select alert_id from notification.alert where entity_ref = '$entity')",
                )
                s.execute("delete from notification.alert where entity_ref = '$entity'")
            }
        }

    @Test
    fun `INV-AC-27270 tenant T2 cannot read or insert a row of T1`() {
        val e = entity()
        seedCommitted(e)
        try {
            bound(b) { c, _ ->
                alertRows(c, e) shouldBe emptyList<String>()
                recipientRows(c, e) shouldBe emptyList<String>()
            }
            bound(b) { _, store -> shouldThrow<Exception> { store.raise(draft(a, e + "-x")) } }
            bound(b) { c, _ ->
                shouldThrow<SQLException> {
                    c.createStatement().use {
                        it.execute(
                            "insert into notification.alert_recipient (tenant_id, alert_id, user_id) " +
                                "values ('$a', gen_random_uuid(), 'intruder')",
                        )
                    }
                }
            }
            bound(a) { c, _ -> recipientRows(c, e) shouldBe listOf("u1", "u2") }
        } finally {
            purge(e)
        }
    }

    @Test
    fun `INV-AC-27270 the runtime role cannot update or delete a recipient row`() {
        val e = entity()
        listOf(
            "update notification.alert_recipient set user_id = 'x'",
            "delete from notification.alert_recipient",
        ).forEach { sql ->
            bound(a) { c, store ->
                store.raise(draft(a, e)) shouldBe true
                shouldThrow<SQLException> { c.createStatement().use { it.execute(sql) } }
            }
        }
    }

    @Test
    fun `INV-AC-27270 an alert_class other than SECURITY is refused and nothing is written`() {
        val e = entity()
        bound(a) { c, store ->
            AlertClass.entries.filter { it != AlertClass.SECURITY }.forEach { cls ->
                shouldThrow<IllegalArgumentException> { store.raise(draft(a, e, cls = cls)) }
            }
            alertRows(c, e) shouldBe emptyList<String>()
            recipientRows(c, e) shouldBe emptyList<String>()
        }
    }

    @Test
    fun `INV-AC-27270 a failure after the alert insert leaves neither row`() {
        val e = entity()
        // a repeated recipient violates the primary key after the alert row was inserted; the caller's transaction
        // is rolled back, so no row of either table is left
        bound(a) { c, store ->
            shouldThrow<Exception> { store.raise(draft(a, e, listOf("u1", "u1"))) }
            c.rollback()
            alertRows(c, e) shouldBe emptyList<String>()
            recipientRows(c, e) shouldBe emptyList<String>()
        }
    }
}
