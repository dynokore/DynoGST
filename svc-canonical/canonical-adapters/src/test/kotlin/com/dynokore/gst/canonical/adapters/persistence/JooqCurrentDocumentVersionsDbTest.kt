package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.DocumentKit
import com.dynokore.gst.canonical.adapters.DocumentSql
import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.LocalDate
import java.util.UUID

/** `JooqCurrentDocumentVersions` over the runtime login with row-level security on (INV-AC-26792 case 10). */
class JooqCurrentDocumentVersionsDbTest {
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
                    listOf("document", "document_version", "document_line", "document_subtotal", "document_party")
                        .plus(listOf("inbound_event", "period_span"))
                        .associate { DSL.name("canonical", it) to TableClass.TENANT },
                ),
                tenants,
                transaction,
            ).dsl()
    private val store = JooqDocumentStore(dsl)
    private val adapter = JooqCurrentDocumentVersions(dsl, tenants)
    private val day = LocalDate.of(2026, 3, 15)
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val doc = UUID.randomUUID()
    private val canonicalV1 = DocumentKit.defaultCanonical("V1")
    private val canonicalV2 = DocumentKit.defaultCanonical("V2")

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    private fun version(canonical: String) =
        NewVersion(
            canonical,
            ScopeVerdict.IN_SCOPE,
            "scope-1",
            "map-1",
            null,
            SpecificationVersion.PINT_SG,
            day,
            "svc:x",
        )

    /** T1: a document with two versions, a prescribed period of entity ENT-1 and a posting row. */
    private fun seed() {
        val periodId = UUID.randomUUID()
        DocumentSql.rows(
            "insert into canonical.period_span (tenant_id, entity_ref, period_ref, period_start, period_end, " +
                "period_id) values (?, 'ENT-1', '2026-Q1', '2026-01-01', '2026-03-31', ?) returning 1",
            t1,
            periodId,
        )
        val document =
            NewDocument(
                doc,
                t1,
                entity,
                null,
                Direction.PURCHASE,
                380,
                "INV-$doc",
                "issuer-1",
                2026,
                null,
                DocumentState.READY,
                periodId,
                version(canonicalV1),
            )
        asTenant(t1) { store.insert(document) }
        DocumentSql.rows(
            "insert into canonical.inbound_event (tenant_id, entity_id, doc_uuid, version, event_kind, " +
                "document_function) values (?, ?, ?, 1, 'POSTING', 'INVOICE') returning 1",
            t1,
            entity,
            doc,
        )
        asTenant(t1) { store.createVersion(doc, 1, version(canonicalV2)) }
    }

    @Test
    fun `INV-AC-26792 case 10 read returns the highest version of the document of the tenant with its stored facts`() {
        seed()
        val read = asTenant(t1) { adapter.read(t1, doc) }
        checkNotNull(read)
        read.version shouldBe 2
        DocumentSql.one("select ?::jsonb = ?::jsonb", read.content.canonical, canonicalV2) shouldBe "t"
        read.content.transactionDate shouldBe day
        read.facts.docUuid shouldBe doc
        read.facts.entityRef shouldBe "ENT-1"
        read.facts.version shouldBe 2
        read.facts.prescribedPeriodRef shouldBe "2026-Q1"
    }

    @Test
    fun `INV-AC-26792 case 10 read returns null for the same Doc UUID as another tenant and for an unknown Doc UUID`() {
        seed()
        asTenant(t2) { adapter.read(t2, doc) } shouldBe null
        asTenant(t2) { adapter.read(t1, doc) } shouldBe null
        asTenant(t1) { adapter.read(t1, UUID.randomUUID()) } shouldBe null
    }

    @Test
    fun `INV-AC-26792 case 10 without row-level security the tenant predicate alone hides the document of another tenant`() {
        seed()
        CanonicalDb.superuser().use { connection ->
            // The owner login bypasses row-level security, so only the adapter's own tenant predicates are left.
            val unfiltered = JooqCurrentDocumentVersions(DSL.using(connection, SQLDialect.POSTGRES), tenants)
            tenants.bind(TenantId(t1), SignedBindings.ACTOR) { unfiltered.read(t1, doc)?.version } shouldBe 2
            tenants.bind(TenantId(t2), SignedBindings.ACTOR) { unfiltered.read(t2, doc) } shouldBe null
            tenants.bind(TenantId(t2), SignedBindings.ACTOR) { unfiltered.read(t1, doc) } shouldBe null
        }
    }
}
