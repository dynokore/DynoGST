package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Probes of the migration `submission_type_not_null` (INV-T-27288; INV-AC-27287). */
class SubmissionTypeNotNullMigrationTest {
    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    private fun insert(
        c: Connection,
        rest: String,
    ) = c.exec("insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref$rest")

    private fun count(
        c: Connection,
        doc: String,
    ) = c.strings("select count(*)::text from submission.submission where doc_uuid = '$doc'")

    @Test
    fun `INV-AC-27287 the column submission_type is not nullable and has no default`() {
        SubmissionDb.superuser().use { c ->
            c.strings(
                "select is_nullable || ':' || coalesce(column_default, 'null') from information_schema.columns " +
                    "where table_schema = 'submission' and table_name = 'submission' " +
                    "and column_name = 'submission_type'",
            ) shouldBe listOf("NO:null")
        }
    }

    @Test
    fun `INV-AC-27287 an insert that omits submission_type is refused with SQLState 23502 and leaves no row`() {
        val doc = UUID.randomUUID().toString()
        val tenant = UUID.randomUUID()
        SubmissionDb.superuser().use { c ->
            try {
                sqlState { insert(c, ") values ('$tenant', '$doc', 1, 'SENT', 'E-001')") } shouldBe "23502"
                count(c, doc) shouldBe listOf("0")
            } finally {
                c.exec("delete from submission.submission where doc_uuid = '$doc'")
            }
        }
    }

    @Test
    fun `INV-AC-27287 an insert that sets submission_type to null is refused with SQLState 23502`() {
        val doc = UUID.randomUUID().toString()
        val tenant = UUID.randomUUID()
        SubmissionDb.superuser().use { c ->
            try {
                sqlState {
                    insert(c, ", submission_type) values ('$tenant', '$doc', 1, 'SENT', 'E-001', null)")
                } shouldBe "23502"
                count(c, doc) shouldBe listOf("0")
            } finally {
                c.exec("delete from submission.submission where doc_uuid = '$doc'")
            }
        }
    }

    @Test
    fun `INV-AC-27287 the four names are accepted and other values are refused with SQLState 23514`() {
        val doc = UUID.randomUUID().toString()
        val tenant = UUID.randomUUID()
        SubmissionDb.superuser().use { c ->
            try {
                fun put(type: String) {
                    c.exec("delete from submission.submission where doc_uuid = '$doc'")
                    insert(c, ", submission_type) values ('$tenant', '$doc', 1, 'SENT', 'E-001', '$type')")
                }
                listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3").forEach { sqlState { put(it) } shouldBe null }
                listOf("TYPE_4", "1A", "type_1a", "").forEach { sqlState { put(it) } shouldBe "23514" }
            } finally {
                c.exec("delete from submission.submission where doc_uuid = '$doc'")
            }
        }
    }
}
