package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.SignedBindings
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
import java.time.Clock
import java.util.UUID

/** The marker table of `IdentityTripleReprocessingRequested` over the runtime login, with row-level security on. */
class JooqIdentityTripleRequestMarkersTest {
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
                    mapOf(DSL.name("canonical", "identity_triple_request_marker") to TableClass.TENANT),
                ),
                tenants,
                transaction,
            ).dsl()
    private val markers = JooqIdentityTripleRequestMarkers(dsl, Clock.systemUTC())
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()

    private fun <T> asTenant(
        id: UUID,
        block: () -> T,
    ): T = tenants.bind(TenantId(id), SignedBindings.ACTOR) { transaction.inTransaction(block) }

    private fun visibleRows(): Int = dsl.fetchCount(DSL.table(DSL.name("canonical", "identity_triple_request_marker")))

    @Test
    fun `INV-AC-26779 case 10 a second insert of the same tenant and event id is reported as already present`() {
        val event = "ev-${UUID.randomUUID()}"
        asTenant(tenantA) { markers.markIfNew(tenantA, event) } shouldBe true
        asTenant(tenantA) { markers.markIfNew(tenantA, event) } shouldBe false
    }

    @Test
    fun `INV-AC-26779 case 11 a row of tenant A is invisible to a session bound to tenant B`() {
        val event = "ev-${UUID.randomUUID()}"
        asTenant(tenantA) { markers.markIfNew(tenantA, event) } shouldBe true
        asTenant(tenantA) { visibleRows() } shouldBe 1
        asTenant(tenantB) { visibleRows() } shouldBe 0
        asTenant(tenantB) { markers.markIfNew(tenantB, event) } shouldBe true
    }
}
