package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqSourceDocumentReferences
import com.dynokore.gst.evidence.adapters.persistence.JooqTenantPackageCandidates
import com.dynokore.gst.evidence.adapters.persistence.PackageSizeRefusal
import com.dynokore.gst.evidence.application.export.AuditPackClassSource
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-25917: the database sources of the audit pack read only the items of the requested entity and period of
 * the bound tenant. T1 and T2 each hold two entities (ENT-A, ENT-B) and two periods (P1, P2), one row per scope in
 * each of the four chain tables and in the document locator. The reconciliation work-paper source is not covered
 * here: a work-paper has no period column, its period is only in the sealed object bytes (gap reported on the task).
 */
class AuditPackCandidatesDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val entities = listOf("ENT-A", "ENT-B")
    private val periods = listOf("P1", "P2")
    private val chainTables =
        mapOf(
            PackageContentClass.TRANSMITTED_XML to "evidence_payload",
            PackageContentClass.ACKNOWLEDGEMENTS to "acknowledgement",
            PackageContentClass.DECISION_TRACES to "decision_trace",
            PackageContentClass.AUDIT_LOG to "audit_log",
        )

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    /** The seq of a row: unique per tenant, so a name of T1 never equals a name of T2. */
    private fun seqOf(tenant: UUID) = if (tenant == t1) 1L else 11L

    private fun docOf(
        tenant: UUID,
        entity: String,
        period: String,
    ) = UUID.nameUUIDFromBytes("$tenant/$entity/$period".toByteArray())

    private fun digest(
        entity: String,
        period: String,
    ): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(entity.toByteArray())
        d.update(0)
        d.update(period.toByteArray())
        return HexFormat.of().formatHex(d.digest()).take(16)
    }

    private fun chainName(
        tenant: UUID,
        entity: String,
        period: String,
    ) = "${seqOf(tenant)}-${digest(entity, period)}"

    private fun seed(
        tenant: UUID,
        content: ByteArray = ByteArray(10) { 7 },
    ) = tx(tenant) { dsl ->
        for (table in chainTables.values) {
            for (entity in entities) {
                for (period in periods) {
                    dsl.fetch(
                        "select evidence.create_retention_partition(cast(? as regclass), ?, ?, ?, ? || chr(1))",
                        "evidence.$table",
                        tenant,
                        entity,
                        period,
                        period,
                    )
                    dsl.execute(
                        "insert into evidence.$table (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                            "content, content_hash, recorded_at) " +
                            "values (gen_random_uuid(), ?, ?, ?, ?, 'T', ?, ?, now())",
                        tenant,
                        entity,
                        period,
                        seqOf(tenant),
                        content,
                        "sha256:" + "a".repeat(64),
                    )
                }
            }
        }
        seedLocators(dsl, tenant)
    }

    private fun seedLocators(
        dsl: DSLContext,
        tenant: UUID,
    ) {
        for (entity in entities) {
            for (period in periods) {
                dsl.execute(
                    "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                        "transaction_date, trace_reference, source_event_id, recorded_at, source_document_ref) " +
                        "values (?, ?, 1, ?, ?, date '2026-01-15', 'trace', ?, now(), ?)",
                    tenant,
                    docOf(tenant, entity, period),
                    entity,
                    period,
                    UUID.randomUUID().toString(),
                    "dms://$tenant/$entity/$period",
                )
            }
        }
    }

    private fun fixture() {
        seed(t1)
        seed(t2)
    }

    private fun source(
        contentClass: PackageContentClass,
        dsl: DSLContext,
        maxBytes: Long,
    ): AuditPackClassSource =
        if (contentClass == PackageContentClass.SOURCE_DOCUMENT_REFERENCES) {
            JooqSourceDocumentReferences(dsl, maxBytes)
        } else {
            JooqTenantPackageCandidates(dsl, maxBytes).scopedSources().getValue(contentClass)
        }

    private fun expected(
        contentClass: PackageContentClass,
        tenant: UUID,
        entity: String,
        period: String,
    ) = if (contentClass == PackageContentClass.SOURCE_DOCUMENT_REFERENCES) {
        "${docOf(tenant, entity, period)}-1"
    } else {
        chainName(tenant, entity, period)
    }

    private fun names(
        contentClass: PackageContentClass,
        tenant: UUID,
        entity: String,
        period: String,
        maxBytes: Long = 1_000_000,
    ): List<String> =
        tx(tenant) { dsl ->
            source(contentClass, dsl, maxBytes).candidates(TenantId(tenant), entity, period).map { it.item.name }
        }

    /** A superuser connection is not under row-level security, so it sees both tenants: only the predicate hides T2. */
    private fun namesSeeingBothTenants(
        contentClass: PackageContentClass,
        tenant: UUID,
        entity: String,
        period: String,
    ): List<String> =
        EvidenceDb.superuser().use { c ->
            source(contentClass, DSL.using(c, SQLDialect.POSTGRES), 1_000_000)
                .candidates(TenantId(tenant), entity, period)
                .map { it.item.name }
        }

    private fun prove(contentClass: PackageContentClass) {
        fixture()
        for (tenant in listOf(t1, t2)) {
            for (entity in entities) {
                for (period in periods) {
                    val want = listOf(expected(contentClass, tenant, entity, period))
                    names(contentClass, tenant, entity, period) shouldBe want
                    namesSeeingBothTenants(contentClass, tenant, entity, period) shouldBe want
                }
            }
        }
    }

    @Test
    fun `INV-AC-25917 TRANSMITTED_XML returns only the item of the entity, period and tenant`() =
        prove(PackageContentClass.TRANSMITTED_XML)

    @Test
    fun `INV-AC-25917 ACKNOWLEDGEMENTS returns only the item of the entity, period and tenant`() =
        prove(PackageContentClass.ACKNOWLEDGEMENTS)

    @Test
    fun `INV-AC-25917 DECISION_TRACES returns only the item of the entity, period and tenant`() =
        prove(PackageContentClass.DECISION_TRACES)

    @Test
    fun `INV-AC-25917 AUDIT_LOG returns only the item of the entity, period and tenant`() =
        prove(PackageContentClass.AUDIT_LOG)

    @Test
    fun `INV-AC-25917 SOURCE_DOCUMENT_REFERENCES returns only the item of the entity, period and tenant`() =
        prove(PackageContentClass.SOURCE_DOCUMENT_REFERENCES)

    @Test
    fun `INV-AC-25917 a scope with several rows returns them in seq order and none of another scope`() {
        fixture()
        tx(t1) { dsl ->
            dsl.execute(
                "insert into evidence.evidence_payload (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                    "content, content_hash, recorded_at) " +
                    "values (gen_random_uuid(), ?, 'ENT-A', 'P1', 2, 'T', ?, ?, now())",
                t1,
                ByteArray(10) { 7 },
                "sha256:" + "a".repeat(64),
            )
        }
        val d = digest("ENT-A", "P1")
        names(PackageContentClass.TRANSMITTED_XML, t1, "ENT-A", "P1") shouldBe listOf("1-$d", "2-$d")
    }

    @Test
    fun `INV-AC-25917 a limit below the bytes of the scope refuses the scoped read and a limit at them does not`() {
        fixture()
        // The scope holds 10 bytes in each of the four chain tables (40); the tenant holds 160.
        for (contentClass in chainTables.keys) {
            shouldThrow<PackageSizeRefusal> { names(contentClass, t1, "ENT-A", "P1", maxBytes = 39) }
            names(contentClass, t1, "ENT-A", "P1", maxBytes = 40).size shouldBe 1
        }
        val reference = "dms://$t1/ENT-A/P1"
        val refs = PackageContentClass.SOURCE_DOCUMENT_REFERENCES
        shouldThrow<PackageSizeRefusal> { names(refs, t1, "ENT-A", "P1", maxBytes = reference.length - 1L) }
        names(refs, t1, "ENT-A", "P1", maxBytes = 1_000L).size shouldBe 1
    }

    @Test
    fun `INV-AC-25917 a read under another bound tenant returns nothing of the first tenant`() {
        fixture()
        for (contentClass in chainTables.keys + PackageContentClass.SOURCE_DOCUMENT_REFERENCES) {
            tx(t2) { dsl ->
                source(contentClass, dsl, 1_000_000).candidates(TenantId(t1), "ENT-A", "P1").size
            } shouldBe 0
        }
    }
}
