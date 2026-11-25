package com.dynokore.gst.evidence.adapters

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** INV-AC-26996: `evidence.retention_notice_tenants()` and the rights around it, over the real PostgreSQL. */
class RetentionNoticeTenantsDbTest {
    private val json = JsonMapper.builder().build()

    private fun Connection.one(sql: String): String? =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private fun Connection.holderRow(
        tenant: UUID,
        ref: String,
    ) {
        prepareStatement(
            "insert into evidence.offboarding_custody_holder values (?, ?, 'N', 'n@example.test', null, null, now())",
        ).use {
            it.setObject(1, tenant)
            it.setString(2, ref)
            it.executeUpdate()
        }
    }

    private fun Connection.partitionRow(tenant: UUID) {
        prepareStatement(
            "insert into evidence.retention_partition (partition_id, tenant_id, entity_ref, period_from, period_to, " +
                "state) values (?, ?, 'E1', '2019-Q1', '2019-Q1', 'ATTACHED')",
        ).use {
            it.setString(1, "rp_" + UUID.randomUUID().toString().replace("-", ""))
            it.setObject(2, tenant)
            it.executeUpdate()
        }
    }

    /** The function as the runtime login calls it, under the platform tenant's binding, as a job reads its source. */
    private fun call(url: String): String =
        DriverManager.getConnection(url, EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, TenantId.PLATFORM.value)
            checkNotNull(c.one("select evidence.retention_notice_tenants()::text")).also { c.rollback() }
        }

    @Test
    fun `INV-AC-26996 the function returns exactly the tenants that have a holder row, once each and in order`() {
        val first = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val second = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val partitionOnly = UUID.fromString("00000000-0000-0000-0000-0000000000c3")
        EvidenceDb.withScratchDatabase("select 1") { url ->
            call(url) shouldBe "[]"
            EvidenceDb.superuserOf(url).use {
                it.holderRow(second, "OFF-1")
                it.holderRow(first, "OFF-1")
                it.holderRow(first, "OFF-2")
                it.partitionRow(partitionOnly)
                it.partitionRow(first)
            }
            // exact equality of the objects: no other tenant and no other member
            json.readTree(call(url)) shouldBe
                json.readTree("""[{"tenant_id": "$first"}, {"tenant_id": "$second"}]""")
        }
    }

    private fun fact(sql: String): String? = EvidenceDb.superuser().use { it.one(sql) }

    @Test
    fun `INV-AC-26996 the function is owned by evidence_sys, a stable definer function with a fixed search path`() {
        fact(
            "select pg_get_userbyid(proowner) || '|' || prosecdef::text || '|' || provolatile::text || '|' || " +
                "array_to_string(proconfig, ';') || '|' || pg_get_function_result(oid) from pg_proc " +
                "where oid = 'evidence.retention_notice_tenants()'::regprocedure",
        ) shouldBe "evidence_sys|true|s|search_path=pg_catalog, evidence, pg_temp|jsonb"
    }

    @Test
    fun `INV-AC-26996 public cannot execute the function and evidence_runtime can`() {
        val function = "'evidence.retention_notice_tenants()'"
        fact("select has_function_privilege('public', $function, 'execute')::text") shouldBe "false"
        fact("select has_function_privilege('evidence_runtime', $function, 'execute')::text") shouldBe "true"
        fact("select has_schema_privilege('evidence_sys', 'evidence', 'create')::text") shouldBe "false"
    }

    @Test
    fun `INV-AC-26996 evidence_sys selects the tenant column of the holder table and no other column`() {
        fact(
            "select string_agg(a.attname, ',' order by a.attname) from pg_attribute a " +
                "where a.attrelid = 'evidence.offboarding_custody_holder'::regclass and a.attnum > 0 " +
                "and not a.attisdropped " +
                "and has_column_privilege('evidence_sys', a.attrelid, a.attnum, 'select')",
        ) shouldBe "tenant_id"
        listOf("custody_holder_name", "custody_holder_email", "custody_holder_mobile").forEach {
            fact(
                "select has_column_privilege('evidence_sys', 'evidence.offboarding_custody_holder', '$it', " +
                    "'select')::text",
            ) shouldBe "false"
        }
        fact(
            "select has_table_privilege('evidence_sys', 'evidence.offboarding_custody_holder', " +
                "'select,insert,update,delete')::text",
        ) shouldBe "false"
    }

    @Test
    fun `INV-AC-26996 the runtime login keeps select and insert only on the holder table`() {
        fact(
            "select string_agg(privilege_type, ',' order by privilege_type) " +
                "from information_schema.role_table_grants where grantee = 'evidence_runtime' " +
                "and table_schema = 'evidence' and table_name = 'offboarding_custody_holder'",
        ) shouldBe "INSERT,SELECT"
    }
}
