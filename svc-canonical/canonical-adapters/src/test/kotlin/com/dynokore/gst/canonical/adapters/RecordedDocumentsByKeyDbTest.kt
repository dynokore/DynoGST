package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqRecordedDocumentsByKey
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.RecordedDocument
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID

/**
 * INV-AC-27994: the lookup of declared source keys over the schema of svc-canonical, as the `canonical_runtime` login
 * bound to a tenant (row security applies).
 */
class RecordedDocumentsByKeyDbTest {
    private val tenants = TenantContextImpl()
    private val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(
                    listOf("document", "inbound_event", "source_event").associate {
                        DSL.name("canonical", it) to TableClass.TENANT
                    },
                ),
                tenants,
                transaction,
            ).dsl()
    private val adapter = JooqRecordedDocumentsByKey(dsl, tenants)

    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val e1 = UUID.randomUUID()
    private val e2 = UUID.randomUUID()
    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()
    private val d3 = UUID.randomUUID()
    private val d4 = UUID.randomUUID()
    private val d5 = UUID.randomUUID()
    private val d6 = UUID.randomUUID()

    private fun record(
        tenant: UUID,
        entity: UUID,
        doc: UUID,
        system: String,
        key: String,
        function: String?,
        revision: Long = 1,
    ) {
        if (DocumentSql.one("select count(*) from canonical.document where doc_uuid = ?", doc) == "0") {
            DocumentSql.execute(
                "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                    "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, " +
                    "current_version, state) values ('$doc', '$tenant', '$entity', 'SALE', 380, 'N-$doc', 'i', " +
                    "2026, '2026-03-15', '2026-03-15', 1, 'READY')",
            )
        }
        DocumentSql.execute(
            "insert into canonical.source_event (tenant_id, source_system, source_key, source_revision, doc_uuid, " +
                "result) values ('$tenant', '$system', '$key', $revision, '$doc', '{}'::jsonb)",
        )
        if (function != null) {
            DocumentSql.execute(
                "insert into canonical.inbound_event (tenant_id, entity_id, doc_uuid, version, event_kind, " +
                    "document_function) values ('$tenant', '$entity', '$doc', 1, 'POSTING', '$function')",
            )
        }
    }

    /** One run of the data: every test uses its own source system, so the shared database never collides. */
    private fun seed(system: String) {
        record(t1, e1, d1, system, "K1", "CREDIT_NOTE")
        record(t1, e1, d2, system, "K2", "INVOICE")
        record(t1, e2, d3, system, "K3", "CREDIT_NOTE")
        record(t2, e1, d4, system, "K1", "CREDIT_NOTE")
        record(t1, e1, d5, system, "K5", null)
    }

    private fun lookup(
        tenant: UUID,
        system: String,
        keys: List<String>,
    ) = tenants.bind(TenantId(tenant), SignedBindings.ACTOR) {
        transaction.inTransaction { adapter.lookup(tenant, system, keys) }
    }

    @Test
    fun `INV-AC-27994 L1 the three resolved keys answer the whole recorded document`() {
        val s = "S1-" + UUID.randomUUID()
        seed(s)
        val answer = lookup(t1, s, listOf("K1", "K2", "K3", "K5", "K9"))
        answer["K1"] shouldBe RecordedDocument(d1, e1, DocumentFunction.CREDIT_NOTE)
        answer["K2"] shouldBe RecordedDocument(d2, e1, DocumentFunction.INVOICE)
        answer["K3"] shouldBe RecordedDocument(d3, e2, DocumentFunction.CREDIT_NOTE)
    }

    @Test
    fun `INV-AC-27994 L2 a key with no stored function and a key recorded nowhere are absent`() {
        val s = "S1-" + UUID.randomUUID()
        seed(s)
        val answer = lookup(t1, s, listOf("K1", "K2", "K3", "K5", "K9"))
        answer.containsKey("K5") shouldBe false
        answer.containsKey("K9") shouldBe false
        answer.keys shouldBe setOf("K1", "K2", "K3")
    }

    @Test
    fun `INV-AC-27994 L3 another source system answers no entry`() {
        val s = "S1-" + UUID.randomUUID()
        seed(s)
        lookup(t1, "S2-" + UUID.randomUUID(), listOf("K1")) shouldBe emptyMap()
        lookup(t1, s + "x", listOf("K1")) shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-27994 L4 tenant isolation the entry of K1 is never the document of another tenant`() {
        val s = "S1-" + UUID.randomUUID()
        seed(s)
        lookup(t1, s, listOf("K1"))["K1"]?.docUuid shouldBe d1
        lookup(t2, s, listOf("K1"))["K1"]?.docUuid shouldBe d4
        lookup(t2, s, listOf("K2")) shouldBe emptyMap()
        shouldThrow<IllegalStateException> {
            tenants.bind(TenantId(t1), SignedBindings.ACTOR) {
                transaction.inTransaction { adapter.lookup(t2, s, listOf("K1")) }
            }
        }
    }

    @Test
    fun `INV-AC-27994 L5 an empty key list answers an empty map and reads nothing`() {
        lookup(t1, "S1-" + UUID.randomUUID(), emptyList()) shouldBe emptyMap()
        // no query: an unbound-to-DB failure would show; the adapter over a DSL that must not be used
        val unused =
            JooqRecordedDocumentsByKey(
                DSL.using(SQLDialect.POSTGRES),
                tenants,
            )
        tenants.bind(TenantId(t1), SignedBindings.ACTOR) {
            unused.lookup(t1, "S", emptyList()) shouldBe emptyMap()
        }
    }

    @Test
    fun `INV-AC-27994 L6 a key naming two Doc UUIDs is absent`() {
        val s = "S1-" + UUID.randomUUID()
        seed(s)
        record(t1, e1, d6, s, "K1", "CREDIT_NOTE", revision = 2)
        val answer = lookup(t1, s, listOf("K1", "K2"))
        answer.containsKey("K1") shouldBe false
        answer["K2"] shouldBe RecordedDocument(d2, e1, DocumentFunction.INVOICE)
    }
}
