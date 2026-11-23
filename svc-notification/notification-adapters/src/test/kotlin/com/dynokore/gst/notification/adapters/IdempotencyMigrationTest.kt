package com.dynokore.gst.notification.adapters

import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-AC-28106 (INV-T-28107): the idempotency migration is the output of service-runtime's `IdempotencySchema.ddl` of
 * the pinned platform-libs version after its header comment. A change of the generator, or an edit by hand, fails here.
 * A database migrated through all versions holds the table once.
 */
class IdempotencyMigrationTest {
    private val file = "db/migration/V202611231100__notification_idempotency.sql"

    private fun body(text: String): String = text.lines().dropWhile { it.startsWith("--") }.joinToString("\n")

    private val generated: String
        get() = IdempotencySchema.ddl(NotificationDb.SCHEMA, NotificationDb.RUNTIME, NotificationDb.SYS)

    private val text: String
        get() = checkNotNull(javaClass.classLoader.getResource(file)) { "$file is missing" }.readText()

    @Test
    fun `INV-AC-28106 the idempotency migration holds exactly the service-runtime DDL of the pinned version`() {
        body(text).trim() shouldBe generated.trim()
    }

    @Test
    fun `INV-AC-28106 one added space or one changed role name in the idempotency migration is a difference`() {
        val spaced = text.replaceFirst("create table", "create  table")
        (spaced != text) shouldBe true
        (body(spaced).trim() == generated.trim()) shouldBe false
        val renamed = text.replaceFirst("to notification_runtime", "to notification_runtimes")
        (renamed != text) shouldBe true
        (body(renamed).trim() == generated.trim()) shouldBe false
    }

    @Test
    fun `INV-AC-28106 a database migrated through all versions holds the idempotency table exactly once`() {
        NotificationDb.superuser().use { connection ->
            connection.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from pg_tables where schemaname = 'notification' " +
                            "and tablename = 'idempotency_key'",
                    ).use {
                        it.next()
                        it.getInt(1) shouldBe 1
                    }
            }
        }
    }
}
