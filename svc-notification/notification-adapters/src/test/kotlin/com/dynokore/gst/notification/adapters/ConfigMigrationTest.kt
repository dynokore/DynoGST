package com.dynokore.gst.notification.adapters

import com.dynokore.platform.configresolver.ConfigSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-AC-28106 (INV-T-28107): the config migration is the output of config-resolver's `ConfigSchema.ddl` of the pinned
 * platform-libs version after its header comment. A change of the generator, or an edit by hand, fails here. A database
 * migrated through all versions holds each object once.
 */
class ConfigMigrationTest {
    private val file = "db/migration/V202611231000__notification_config.sql"

    private fun body(text: String): String = text.lines().dropWhile { it.startsWith("--") }.joinToString("\n")

    private val generated: String
        get() = ConfigSchema.ddl(NotificationDb.SCHEMA, NotificationDb.RUNTIME, NotificationDb.SYS)

    private val text: String
        get() = checkNotNull(javaClass.classLoader.getResource(file)) { "$file is missing" }.readText()

    @Test
    fun `INV-AC-28106 the config migration holds exactly the config-resolver DDL of the pinned version`() {
        body(text).trim() shouldBe generated.trim()
    }

    @Test
    fun `INV-AC-28106 one added space or one changed role name in the config migration is a difference`() {
        val spaced = text.replaceFirst("create table", "create  table")
        (spaced != text) shouldBe true
        (body(spaced).trim() == generated.trim()) shouldBe false
        val renamed = text.replaceFirst("to notification_runtime", "to notification_runtimes")
        (renamed != text) shouldBe true
        (body(renamed).trim() == generated.trim()) shouldBe false
    }

    @Test
    fun `INV-AC-28106 a database migrated through all versions holds each config object exactly once`() {
        val tables =
            listOf(
                "config_setting",
                "config_installation_setting",
                "config_resolved_value",
                "config_relaxation_acknowledgement",
            )
        NotificationDb.superuser().use { connection ->
            tables.forEach { table ->
                count(
                    connection,
                    "select count(*) from pg_tables where schemaname = 'notification' and tablename = '$table'",
                ) shouldBe 1
            }
            listOf(ConfigSchema.PURGE_TENANT, ConfigSchema.WRITE_INSTALLATION_SETTING).forEach { function ->
                count(
                    connection,
                    "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                        "where n.nspname = 'notification' and p.proname = '$function'",
                ) shouldBe 1
            }
        }
    }

    private fun count(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getInt(1)
            }
        }
}
