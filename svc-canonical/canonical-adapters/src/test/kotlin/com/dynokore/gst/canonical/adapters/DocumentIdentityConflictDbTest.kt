package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentStore
import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.application.document.CounterpartyDirectory
import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.DocumentIdentityConflict
import com.dynokore.gst.canonical.application.document.DocumentPartyRefs
import com.dynokore.gst.canonical.application.document.DocumentResultBuilder
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.PartyRefAnswer
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.document.RecordDocumentPipeline
import com.dynokore.gst.canonical.application.document.RecordDocumentService
import com.dynokore.gst.canonical.application.document.RecordPartyRef
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-1768: a recording with a new source key whose identity (tenant, entity, direction, document type, issuer
 * party, document number, fiscal year) equals a recorded document's is blocked with SUB-007 and creates no second
 * Doc UUID.
 * The cases run through RecordDocumentService.record over the real source-event and document stores; the result
 * builder writes the document row through JooqDocumentStore.insert in the record transaction.
 */
class DocumentIdentityConflictDbTest {
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
                    listOf(
                        "document",
                        "document_version",
                        "document_line",
                        "document_subtotal",
                        "document_party",
                        "source_event",
                    ).associate { DSL.name("canonical", it) to TableClass.TENANT },
                ),
                tenants,
                transaction,
            ).dsl()
    private val store = JooqDocumentStore(dsl)

    private fun <T> asTenant(
        tenantId: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(tenantId), SignedBindings.ACTOR, block)

    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    /** The identity values of a recording that the command does not carry as such. */
    private data class Spec(
        val tenantId: UUID,
        val direction: Direction = Direction.SALE,
        val typeCode: Short = TYPE_CODE,
        val issuer: String = "issuer-1",
        val number: String = "N-1",
        val fiscalYear: Short = FISCAL_YEAR,
        val date: LocalDate = LocalDate.of(2026, 3, 15),
    )

    private val specs = mutableMapOf<String, Spec>()

    private val builder =
        DocumentResultBuilder { command, docUuid ->
            val spec = specs.getValue(command.sourceKey)
            store.insert(
                DocumentKit.document(spec.tenantId, entity, spec.date, spec.number, spec.issuer, docUuid).copy(
                    direction = spec.direction,
                    documentTypeCode = spec.typeCode,
                    fiscalYear = spec.fiscalYear,
                ),
            )
            """{"result": "${command.sourceKey}"}""" // the jsonb text form
        }

    private val service =
        RecordDocumentService(
            RecordDocumentPipeline(
                object : CounterpartyDirectory {
                    override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer = PartyRefAnswer.NotFound
                },
                RecordSourceEvent(JooqSourceEventStore(dsl), transaction) { UUID.randomUUID() },
                RecordPartyRef(
                    object : DocumentPartyRefs {
                        override fun upsert(
                            docUuid: UUID,
                            partyRef: String,
                        ) = error("no party reference is recorded here")
                    },
                ),
            ),
            object : EntityConstitutions {
                override fun constitutionOf(
                    tenantId: UUID,
                    entityRef: String,
                ): String? = null
            },
            builder,
        )

    private fun record(
        key: String,
        spec: Spec,
        revision: Long = 1,
    ) = asTenant(spec.tenantId) {
        specs[key] = spec
        service.record(
            RecordDocumentCommand(
                spec.tenantId,
                "entity-1",
                "erp",
                key,
                revision,
                spec.date,
                spec.direction,
                PartyIdentifier("UEN", spec.issuer),
                PartyIdentifier("UEN", "buyer-1"),
                true,
                documentNumber = spec.number,
            ),
        )
    }

    private fun first() = Spec(tenant).also { record("K1", it) }.let { it to documentCount(it) }

    private fun documentCount(spec: Spec): String? =
        DocumentSql.one(
            "select count(*) from canonical.document where tenant_id = ? and document_number = ?",
            spec.tenantId,
            spec.number,
        )

    private fun docUuidOf(spec: Spec): String? =
        DocumentSql.one(
            "select doc_uuid::text from canonical.document where tenant_id = ? and document_number = ?",
            spec.tenantId,
            spec.number,
        )

    private fun sourceRows(
        key: String,
        spec: Spec = Spec(tenant),
    ): String? =
        DocumentSql.one(
            "select count(*) from canonical.source_event where tenant_id = ? and source_key = ?",
            spec.tenantId,
            key,
        )

    private fun recordedAsSecond(spec: Spec) {
        val (firstSpec, _) = first()
        val firstDoc = docUuidOf(firstSpec)
        val second = record("K2", spec)
        second.inserted shouldBe true
        (second.docUuid.toString() == firstDoc) shouldBe false
    }

    private fun refusedAsSecond(spec: Spec) {
        val (firstSpec, _) = first()
        val firstDoc = docUuidOf(firstSpec)
        val refused = shouldThrow<DocumentIdentityConflict> { record("K2", spec) }
        refused.code shouldBe "SUB-007"
        refused.code shouldBe DocumentIdentityConflict.CODE
        documentCount(firstSpec) shouldBe "1"
        docUuidOf(firstSpec) shouldBe
            firstDoc
        sourceRows("K2", spec) shouldBe "0"
        sourceRows("K1") shouldBe "1"
    }

    @Test
    fun `INV-AC-1768 a all seven identity values equal is refused with SUB-007 and no second Doc UUID exists`() {
        refusedAsSecond(Spec(tenant))
    }

    @Test
    fun `INV-AC-1768 b only the issuer party differs is recorded as a second document`() {
        recordedAsSecond(Spec(tenant, issuer = "issuer-2"))
    }

    @Test
    fun `INV-AC-1768 c only the fiscal year differs is recorded`() {
        recordedAsSecond(Spec(tenant, fiscalYear = (FISCAL_YEAR + 1).toShort()))
    }

    @Test
    fun `INV-AC-1768 d only the document type differs is recorded`() {
        recordedAsSecond(Spec(tenant, typeCode = 381))
    }

    @Test
    fun `INV-AC-1768 e only the direction differs is recorded`() {
        recordedAsSecond(Spec(tenant, direction = Direction.PURCHASE))
    }

    @Test
    fun `INV-AC-1768 f only the document number differs is recorded`() {
        recordedAsSecond(Spec(tenant, number = "N-2"))
    }

    @Test
    fun `INV-AC-1768 g only the transaction date differs is refused with SUB-007`() {
        refusedAsSecond(Spec(tenant, date = day.plusDays(1)))
    }

    @Test
    fun `INV-AC-1768 g2 only the transaction date year differs is refused with SUB-007`() {
        refusedAsSecond(Spec(tenant, date = LocalDate.of(2027, 1, 5)))
    }

    @Test
    fun `INV-AC-1768 h the same values under another tenant are recorded`() {
        recordedAsSecond(Spec(UUID.randomUUID()))
    }

    @Test
    fun `INV-AC-1768 i a replay of the first source key answers the stored result and raises nothing`() {
        val firstSpec = Spec(tenant)
        val one = record("K1", firstSpec)
        val again = record("K1", firstSpec)
        again.inserted shouldBe false
        again.docUuid shouldBe one.docUuid
        again.result shouldBe one.result
        documentCount(firstSpec) shouldBe "1"
    }

    @Test
    fun `INV-AC-1768 j a document_pkey violation is not mapped and the original exception propagates`() {
        val doc = DocumentKit.document(tenant, entity, day, "P-1")
        asTenant(tenant) { transaction.inTransaction { store.insert(doc) } }
        val failure =
            shouldThrow<DataAccessException> {
                asTenant(tenant) {
                    transaction.inTransaction {
                        store.insert(DocumentKit.document(tenant, entity, day, "P-2", docUuid = doc.docUuid))
                    }
                }
            }
        failure.shouldNotBeInstanceOf<DocumentIdentityConflict>()
        DocumentSql.sqlState(failure) shouldBe "23505"
    }

    @Test
    fun `INV-AC-1768 k another SQLSTATE from the database is not mapped`() {
        val tooLong = "x".repeat(ISSUER_MAX + 1) // issuer_party_key is varchar(64): SQLSTATE 22001
        val failure =
            shouldThrow<DataAccessException> {
                asTenant(tenant) {
                    transaction.inTransaction {
                        store.insert(DocumentKit.document(tenant, entity, day, issuer = tooLong))
                    }
                }
            }
        failure.shouldNotBeInstanceOf<DocumentIdentityConflict>()
        DocumentSql.sqlState(failure) shouldBe "22001"
    }

    @Test
    fun `INV-AC-1768 l the mapping needs SQLSTATE 23505 and the constraint name by whole-string equality`() {
        val mapped = JooqDocumentStore.isIdentityViolation("23505", "document_identity_key")
        mapped shouldBe true
        JooqDocumentStore.isIdentityViolation("23505", "document_pkey") shouldBe false
        JooqDocumentStore.isIdentityViolation("23505", "document_identity_key_old") shouldBe false
        JooqDocumentStore.isIdentityViolation("23505", "Document_Identity_Key") shouldBe false
        JooqDocumentStore.isIdentityViolation("23505", " document_identity_key") shouldBe false
        JooqDocumentStore.isIdentityViolation("23505", null) shouldBe false
        JooqDocumentStore.isIdentityViolation("23503", "document_identity_key") shouldBe false
        JooqDocumentStore.isIdentityViolation("23514", "document_identity_key") shouldBe false
        JooqDocumentStore.isIdentityViolation(null, "document_identity_key") shouldBe false
    }

    private companion object {
        const val TYPE_CODE: Short = 380
        const val FISCAL_YEAR: Short = 2026
        const val ISSUER_MAX = 64
    }
}
