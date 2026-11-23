package com.dynokore.gst.notification.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * INV-AC-28106 (INV-T-28107): the service-runtime tables of the notification schema are tenant data under forced row
 * level security, read through the runtime login bound to a tenant as the other database tests bind it; the installation
 * setting table is platform-scoped and the runtime login cannot write it.
 */
class ServiceRuntimeTablesDbTest {
    private fun <T> runtime(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }

    private fun Connection.count(table: String): Int =
        createStatement().use { s ->
            s.executeQuery("select count(*) from notification.$table").use {
                it.next()
                it.getInt(1)
            }
        }

    private fun Connection.insertSetting(tenant: UUID) =
        prepareStatement(
            "insert into notification.config_setting (id, tenant_id, vp_key, scope_level, scope_id, option_keys, " +
                "source, effective_from, set_by, reason, recorded_at) values " +
                "(?, ?, 'VP-67', 'TENANT', ?, array['CUSTOM'], 'OVERRIDE', current_date, 'user-1', 'a reason', now())",
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, tenant)
            it.setString(3, tenant.toString())
            it.executeUpdate()
        }

    private fun Connection.insertKey(tenant: UUID) =
        prepareStatement(
            "insert into notification.idempotency_key (tenant_id, principal, idempotency_key, fingerprint, status, " +
                "body, created_at) values (?, 'p1', ?, 'f', 201, '{}', now())",
        ).use {
            it.setObject(1, tenant)
            it.setObject(2, UUID.randomUUID())
            it.executeUpdate()
        }

    /** Writes one row of each table as [tenant] and commits, so another session can look for it. */
    private fun write(tenant: UUID) =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            connection.insertSetting(tenant)
            connection.insertKey(tenant)
            connection.commit()
        }

    @Test
    fun `INV-AC-28106 a tenant reads one row of each written table and another tenant reads none`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        write(t1)
        runtime(t1) {
            it.count("config_setting") shouldBe 1
            it.count("idempotency_key") shouldBe 1
        }
        runtime(t2) {
            it.count("config_setting") shouldBe 0
            it.count("idempotency_key") shouldBe 0
        }
    }

    @Test
    fun `INV-AC-28106 a write with the tenant id of another tenant is refused with SQLSTATE 42501`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        runtime(t1) { shouldThrow<SQLException> { it.insertSetting(t2) }.sqlState shouldBe "42501" }
        runtime(t1) { shouldThrow<SQLException> { it.insertKey(t2) }.sqlState shouldBe "42501" }
    }

    @Test
    fun `INV-AC-28106 the installation setting table has no tenant column and the runtime login cannot insert into it`() {
        NotificationDb.superuser().use { connection ->
            connection.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from information_schema.columns where table_schema = 'notification' " +
                            "and table_name = 'config_installation_setting' and column_name = 'tenant_id'",
                    ).use {
                        it.next()
                        it.getInt(1) shouldBe 0
                    }
                s
                    .executeQuery(
                        "select obj_description('notification.config_installation_setting'::regclass, 'pg_class')",
                    ).use {
                        it.next()
                        it.getString(1) shouldBe "platform-scoped: no tenant data"
                    }
            }
        }
        runtime(UUID.randomUUID()) { connection ->
            shouldThrow<SQLException> {
                connection
                    .prepareStatement(
                        "insert into notification.config_installation_setting (id, vp_key, option_keys, " +
                            "effective_from, set_by, reason, recorded_at) values " +
                            "(?, 'VP-67', array['CUSTOM'], current_date, 'user-1', 'a reason', now())",
                    ).use {
                        it.setObject(1, UUID.randomUUID())
                        it.executeUpdate()
                    }
            }.sqlState shouldBe "42501"
        }
    }
}
