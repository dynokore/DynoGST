package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.persistence.JooqConnectionRecords
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.domain.TenantRef
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
import java.util.UUID

/**
 * The connection record read of `setThroughputCeiling` (INV-T-24959, INV-AC-24957): the tenant predicate on
 * `tenant_id` tells the rows apart even when forced row level security shows the session the rows of another tenant,
 * so a missing predicate cannot be masked by the database.
 */
class ConnectionRecordsTest {
    @Test
    fun `INV-AC-24957 a connection record of tenant A is not found by tenant B even in a session bound to A`() {
        val bound = UUID.randomUUID()
        val bindings = TenantContextImpl()
        val source =
            PGSimpleDataSource().apply {
                setUrl(TransportDb.jdbcUrl)
                user = TransportDb.RUNTIME
                password = TransportDb.RUNTIME_PASSWORD
            }
        val impl =
            TransactionRunnerImpl(
                source,
                bindings,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = TransportDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )
        val dsl =
            TenancyJooq
                .configure(
                    DefaultConfiguration().set(SQLDialect.POSTGRES),
                    TenancyTables.declared(mapOf(DSL.name("transport", "ap_connection") to TableClass.TENANT)),
                    bindings,
                    impl,
                ).dsl()
        val runner =
            object : com.dynokore.platform.tenancykernel.TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T =
                    bindings.bind(TenantId(bound), SignedBindings.ACTOR) { impl.inTransaction(block) }
            }
        val adapter = "a-${UUID.randomUUID().toString().take(12)}"
        runner.inTransaction {
            dsl
                .insertInto(AP_CONNECTION)
                .set(AP_CONNECTION.TENANT_ID, bound)
                .set(AP_CONNECTION.ADAPTER_ID, adapter)
                .set(AP_CONNECTION.AGREED_RATE, 100.toBigDecimal())
                .execute()
        }
        val records = JooqConnectionRecords(dsl, runner)
        records.load(TenantRef(UUID.randomUUID().toString()), adapter) shouldBe null
        records.load(TenantRef(bound.toString()), adapter)?.agreedRate?.compareTo(100.toBigDecimal()) shouldBe 0
    }
}
