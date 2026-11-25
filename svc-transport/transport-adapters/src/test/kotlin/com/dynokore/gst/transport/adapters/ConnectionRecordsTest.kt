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
import com.dynokore.platform.tenancykernel.TransactionRunner
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
    private val bindings = TenantContextImpl()
    private val impl =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(TransportDb.jdbcUrl)
                user = TransportDb.RUNTIME
                password = TransportDb.RUNTIME_PASSWORD
            },
            bindings,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = TransportDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(mapOf(DSL.name("transport", "ap_connection") to TableClass.TENANT)),
                bindings,
                impl,
            ).dsl()

    private fun runnerBoundTo(tenant: UUID) =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T =
                bindings.bind(TenantId(tenant), SignedBindings.ACTOR) { impl.inTransaction(block) }
        }

    @Test
    fun `INV-AC-24957 a connection record of tenant A is not found by tenant B even in a session bound to A`() {
        val bound = UUID.randomUUID()
        val runner = runnerBoundTo(bound)
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

    private fun insert(
        runner: TransactionRunner,
        tenant: UUID,
        agreed: Int?,
        ceiling: Int?,
    ) = runner.inTransaction {
        dsl
            .insertInto(AP_CONNECTION)
            .set(AP_CONNECTION.TENANT_ID, tenant)
            .set(AP_CONNECTION.ADAPTER_ID, "a-${UUID.randomUUID().toString().take(12)}")
            .set(AP_CONNECTION.AGREED_RATE, agreed?.toBigDecimal())
            .set(AP_CONNECTION.TENANT_CEILING, ceiling?.toBigDecimal())
            .execute()
    }

    private fun refusedBy(
        constraint: String?,
        agreed: Int?,
        ceiling: Int?,
    ) {
        val tenant = UUID.randomUUID()
        val failure = runCatching { insert(runnerBoundTo(tenant), tenant, agreed, ceiling) }.exceptionOrNull()
        (failure != null) shouldBe true
        if (constraint != null) {
            generateSequence(failure) { it.cause }.any { it.message?.contains(constraint) == true } shouldBe true
        }
    }

    @Test
    fun `INV-AC-24957 a row with a null agreed rate and a null ceiling is accepted`() {
        val tenant = UUID.randomUUID()
        insert(runnerBoundTo(tenant), tenant, null, null) shouldBe 1
    }

    @Test
    fun `INV-AC-24957 a null agreed rate with a ceiling of 50 is refused by ap_connection_ceiling_needs_rate_check`() =
        refusedBy("ap_connection_ceiling_needs_rate_check", null, 50)

    @Test
    fun `INV-AC-24957 a row with an agreed rate of 0 is still refused`() = refusedBy("agreed_rate", 0, null)

    @Test
    fun `INV-AC-24957 a row with an agreed rate of 100 and a ceiling of 101 is still refused`() =
        refusedBy("tenant_ceiling", 100, 101)

    @Test
    fun `INV-AC-24957 a row with an agreed rate of 100 and a ceiling of 100 is accepted`() {
        val tenant = UUID.randomUUID()
        insert(runnerBoundTo(tenant), tenant, 100, 100) shouldBe 1
    }
}
