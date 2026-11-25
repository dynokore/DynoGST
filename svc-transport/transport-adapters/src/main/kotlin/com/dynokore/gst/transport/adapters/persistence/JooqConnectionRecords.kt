package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.math.BigDecimal
import java.util.UUID

/** The record of an AP connection as the console reads it (INV-REQ-1317): the rates, the entities and the version. */
class ConnectionRecord(
    val adapterRef: String,
    val agreedRate: BigDecimal?,
    val tenantCeiling: BigDecimal?,
    val entityRefs: List<String>,
    val version: Long,
)

/**
 * Reads an AP connection record by `adapter_id` under the tenant predicate (and row-level security): a ref of another
 * tenant is not found. Nothing is written here.
 */
class JooqConnectionRecords(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) {
    fun load(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionRecord? =
        runner.inTransaction {
            dsl
                .select(
                    AP_CONNECTION.AGREED_RATE,
                    AP_CONNECTION.TENANT_CEILING,
                    AP_CONNECTION.ENTITY_REFS,
                    AP_CONNECTION.VERSION,
                ).from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                .and(AP_CONNECTION.ADAPTER_ID.eq(connectionRef))
                .fetchOne()
                ?.let { row ->
                    ConnectionRecord(
                        connectionRef,
                        row.value1(),
                        row.value2(),
                        row.value3().orEmpty().map { checkNotNull(it) },
                        checkNotNull(row.value4()),
                    )
                }
        }
}
