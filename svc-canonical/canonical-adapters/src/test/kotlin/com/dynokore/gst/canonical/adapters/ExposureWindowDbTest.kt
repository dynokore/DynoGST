package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqExposureWindowStore
import com.dynokore.gst.canonical.application.exposure.ExposureWindow
import com.dynokore.gst.canonical.application.exposure.ReadExposureWindow
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
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-28717: the distinct entityRefs of the exposure window over the schema of svc-canonical, as the
 * `canonical_runtime` login bound to a tenant. Every test uses its own tenants and principal.
 */
class ExposureWindowDbTest {
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
                    listOf("document", "document_version").associate {
                        DSL.name("canonical", it) to TableClass.TENANT
                    },
                ),
                tenants,
                transaction,
            ).dsl()
    private val useCase = ReadExposureWindow(JooqExposureWindowStore(dsl, tenants), transaction)

    private val tenant = UUID.randomUUID()
    private val otherTenant = UUID.randomUUID()
    private val principal = "principal-" + UUID.randomUUID() + "-1"
    private val from = Instant.parse("2026-05-01T00:00:00Z")
    private val to = Instant.parse("2026-05-02T00:00:00Z")
    private val inside = Instant.parse("2026-05-01T12:00:00Z")

    /** One document with one version, written by the superuser so that `created_at` is chosen. */
    private fun seed(
        entityRef: String?,
        at: Instant = inside,
        by: String = principal,
        owner: UUID = tenant,
    ) {
        val doc = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val ref = entityRef?.let { "'$it'" } ?: "null"
        DocumentSql.execute(
            "insert into canonical.document (doc_uuid, tenant_id, entity_id, entity_ref, direction, " +
                "document_type_code, document_number, issuer_party_key, fiscal_year, partition_date, " +
                "transaction_date, current_version, state) values ('$doc', '$owner', '$entity', $ref, 'SALE', 380, " +
                "'N-$doc', 'i', 2026, '2026-03-15', '2026-03-15', 1, 'READY')",
        )
        DocumentSql.execute(
            "insert into canonical.document_version (doc_uuid, version, tenant_id, entity_id, partition_date, " +
                "canonical, scope_verdict, scope_pack_version, mapping_pack_version, specification_version, " +
                "created_at, created_by) values ('$doc', 1, '$owner', '$entity', '2026-03-15', '{}'::jsonb, " +
                "'IN_SCOPE', 's', 'm', 'PINT_SG', '$at', '$by')",
        )
    }

    private fun entityRefs(
        by: String = principal,
        bound: UUID = tenant,
    ) = tenants.bind(TenantId(bound), SignedBindings.ACTOR) {
        useCase.entityRefs(ExposureWindow(by, from, to))
    }

    @Test
    fun `INV-AC-28717 E1 the answer is the distinct entityRefs of the principal, look-alikes excluded`() {
        seed("entity-a")
        seed("entity-a")
        seed("entity-b")
        seed("entity-longer", by = principal + "0")
        seed("entity-shorter", by = principal.dropLast(1))
        seed("entity-case", by = principal.uppercase())
        seed("entity-system", by = "system:aggregate-ready")
        entityRefs() shouldBe setOf("entity-a", "entity-b")
    }

    @Test
    fun `INV-AC-28717 E2 recordedFrom is included and recordedTo is excluded`() {
        seed("before", at = from.minusNanos(MICROSECOND))
        seed("at-from", at = from)
        seed("last", at = to.minusNanos(MICROSECOND))
        seed("at-to", at = to)
        entityRefs() shouldBe setOf("at-from", "last")
    }

    @Test
    fun `INV-AC-28717 E3 the rows of another tenant never appear`() {
        seed("mine")
        seed("theirs", owner = otherTenant)
        entityRefs() shouldBe setOf("mine")
        entityRefs(bound = otherTenant) shouldBe setOf("theirs")
    }

    @Test
    fun `INV-AC-28717 E4 one matching row without an entityRef fails the whole read`() {
        seed("entity-a")
        seed(null)
        shouldThrow<IllegalStateException> { entityRefs() }
    }

    @Test
    fun `INV-AC-28717 E5 a row without an entityRef that does not match does not fail the read`() {
        seed("entity-a")
        seed(null, at = from.minusNanos(MICROSECOND))
        seed(null, at = to)
        seed(null, by = principal + "0")
        seed(null, owner = otherTenant)
        entityRefs() shouldBe setOf("entity-a")
    }

    @Test
    fun `INV-AC-28717 E6 a window with no matching version answers no entityRef`() {
        seed("entity-a", by = principal + "0")
        entityRefs() shouldBe emptySet()
    }

    private companion object {
        const val MICROSECOND = 1_000L // the resolution of timestamptz
    }
}
