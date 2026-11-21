package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.persistence.JooqFeedAlerts
import com.dynokore.gst.notification.adapters.persistence.JooqFeedHealthStates
import com.dynokore.gst.notification.application.alerting.FeedAlertDraft
import com.dynokore.gst.notification.application.alerting.FeedHealthState
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-REQ-910, slice 2: the jOOQ adapters of the feed health state and of the COVERAGE alert (INV-AC-26516). */
class FeedHealthAdaptersDbTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val at = Instant.parse("2026-11-15T01:02:03Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    private fun <T> bound(
        tenant: UUID,
        block: (Connection, DSLContext) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(c, DSL.using(c, SQLDialect.POSTGRES))
            } finally {
                c.rollback()
            }
        }

    private fun entity() = "e-" + UUID.randomUUID()

    private fun draft(
        tenant: UUID,
        entity: String,
        control: String = "SEQUENCE",
        cls: AlertClass = AlertClass.COVERAGE,
    ) = FeedAlertDraft(TenantId(tenant), cls, control, entity, "2026-10", at)

    private fun state(
        tenant: UUID,
        entity: String,
        feed: String,
        state: String,
        version: Long? = 1,
    ) = FeedHealthState(TenantId(tenant), feed, entity, "2026-10", "SEQUENCE", state, version, null, emptyList())

    private fun alertStates(
        c: Connection,
        entity: String,
    ): List<List<String?>> =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "select state, alert_class, \"trigger\", period_ref from notification.alert " +
                        "where entity_ref = '$entity' order by \"trigger\", state",
                ).use { r ->
                    generateSequence { if (r.next()) (1..4).map { r.getString(it) } else null }.toList()
                }
        }

    @Test
    fun `INV-AC-26516 a raise inserts one COVERAGE row and a second raise inserts none`() {
        val e = entity()
        bound(a) { c, dsl ->
            val store = JooqFeedAlerts(dsl)
            val first = store.raise(draft(a, e))
            val second = store.raise(draft(a, e))
            second shouldBe first
            alertStates(c, e) shouldBe listOf(listOf("RAISED", "COVERAGE", "SEQUENCE", "2026-10"))
        }
    }

    @Test
    fun `INV-AC-26516 resolve sets RESOLVED for the open alert of the same key only`() {
        val e = entity()
        bound(a) { c, dsl ->
            val store = JooqFeedAlerts(dsl)
            store.raise(draft(a, e, "SEQUENCE"))
            store.raise(draft(a, e, "COUNT"))
            store.resolveIfNoFeedFailed(TenantId(a), e, "2026-11", "SEQUENCE", at) shouldBe false
            store.resolveIfNoFeedFailed(TenantId(a), "other-$e", "2026-10", "SEQUENCE", at) shouldBe false
            store.resolveIfNoFeedFailed(TenantId(a), e, "2026-10", "SEQUENCE", at) shouldBe true
            store.resolveIfNoFeedFailed(TenantId(a), e, "2026-10", "SEQUENCE", at) shouldBe false
            alertStates(c, e) shouldBe
                listOf(
                    listOf("RAISED", "COVERAGE", "COUNT", "2026-10"),
                    listOf("RESOLVED", "COVERAGE", "SEQUENCE", "2026-10"),
                )
        }
    }

    @Test
    fun `INV-AC-26516 resolve does nothing while another feed row is FAILED`() {
        val e = entity()
        bound(a) { c, dsl ->
            val alerts = JooqFeedAlerts(dsl)
            val states = JooqFeedHealthStates(dsl, clock)
            alerts.raise(draft(a, e))
            states.save(state(a, e, "feed-1", "CLEARED"))
            states.save(state(a, e, "feed-2", "FAILED"))
            alerts.resolveIfNoFeedFailed(TenantId(a), e, "2026-10", "SEQUENCE", at) shouldBe false
            alertStates(c, e) shouldBe listOf(listOf("RAISED", "COVERAGE", "SEQUENCE", "2026-10"))
            states.save(state(a, e, "feed-2", "CLEARED", version = 2))
            alerts.resolveIfNoFeedFailed(TenantId(a), e, "2026-10", "SEQUENCE", at) shouldBe true
            alertStates(c, e) shouldBe listOf(listOf("RESOLVED", "COVERAGE", "SEQUENCE", "2026-10"))
        }
    }

    private fun superSql(sql: String) =
        NotificationDb.superuser().use { c -> c.createStatement().use { s -> s.execute(sql) } }

    private fun superRead(sql: String): String? =
        NotificationDb.superuser().use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } }
        }

    @Test
    fun `INV-AC-26516 tenant T2 cannot read or update a row of T1`() {
        val e = entity()
        try {
            superSql(
                "insert into notification.feed_health_state (tenant_id, feed, entity_ref, period, control, " +
                    "state, state_version, updated_at) values ('$a', 'f', '$e', '2026-10', 'SEQUENCE', " +
                    "'FAILED', 1, now())",
            )
            bound(a) { _, dsl ->
                val row = JooqFeedHealthStates(dsl, clock).find(TenantId(a), "f", e, "2026-10", "SEQUENCE")
                row?.state shouldBe "FAILED"
            }
            bound(b) { c, dsl ->
                JooqFeedHealthStates(dsl, clock).find(TenantId(a), "f", e, "2026-10", "SEQUENCE") shouldBe null
                c.createStatement().use { s ->
                    s.executeUpdate("update notification.feed_health_state set state = 'CLEARED'") shouldBe 0
                }
                val foreign = state(a, e, "f", "CLEARED", version = 2)
                shouldThrow<DataAccessException> { JooqFeedHealthStates(dsl, clock).save(foreign) }
            }
            superRead("select state from notification.feed_health_state where entity_ref = '$e'") shouldBe
                "FAILED"
        } finally {
            superSql("delete from notification.feed_health_state where entity_ref = '$e'")
        }
    }

    @Test
    fun `INV-AC-26516 the runtime role cannot delete a state row`() {
        val e = entity()
        bound(a) { c, dsl ->
            JooqFeedHealthStates(dsl, clock).save(state(a, e, "f", "FAILED"))
            val failure =
                shouldThrow<SQLException> {
                    c.createStatement().use { s -> s.execute("delete from notification.feed_health_state") }
                }
            failure.sqlState shouldBe "42501"
        }
    }

    @Test
    fun `INV-AC-26516 an alert_class other than COVERAGE is never written by the adapter`() {
        val e = entity()
        bound(a) { c, dsl ->
            val store = JooqFeedAlerts(dsl)
            AlertClass.entries.filter { it != AlertClass.COVERAGE }.forEach { cls ->
                shouldThrow<IllegalArgumentException> { store.raise(draft(a, e, cls = cls)) }
            }
            alertStates(c, e) shouldBe emptyList()
            store.raise(draft(a, e))
            alertStates(c, e).map { it[1] }.distinct() shouldBe listOf("COVERAGE")
        }
    }

    @Test
    fun `INV-AC-26516 a state row keeps its recipients and a missing version reads back as null`() {
        val e = entity()
        bound(a) { _, dsl ->
            val states = JooqFeedHealthStates(dsl, clock)
            states.save(state(a, e, "f", "FAILED", version = null).copy(recipients = listOf("u1", "u2")))
            val read = checkNotNull(states.find(TenantId(a), "f", e, "2026-10", "SEQUENCE"))
            read.stateVersion shouldBe null
            read.recipients shouldBe listOf("u1", "u2")
            states.save(state(a, e, "f", "CLEARED", version = 5))
            val cleared = checkNotNull(states.find(TenantId(a), "f", e, "2026-10", "SEQUENCE"))
            cleared.stateVersion shouldBe 5L
            cleared.recipients shouldBe emptyList()
        }
    }
}
