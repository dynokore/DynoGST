package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.IntakeReceipt.Companion.INTAKE_RECEIPT
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/** The intake operation that accepted a triple; the names are the values of `intake_operation`. */
internal enum class IntakeOperation { BATCH, TRANSACTIONS }

/** What an intake operation newly accepted: its (entityRef, sourceKey, sourceRevision) and the aggregate type. */
internal data class AcceptedIntake(
    val entityRef: String,
    val sourceKey: String,
    val sourceRevision: Long,
    val aggregateType: AggregateType,
)

/**
 * The submitting principal of every accepted intake in `aggregation.intake_receipt` (INV-AC-28764): the actor bound
 * for the call, never a role (INV-Q-10647). It opens no transaction: every call is made inside the transaction that
 * stores the intake, so the data and its receipt commit or roll back together.
 */
internal class JooqIntakeReceipts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) {
    /** The principal bound for the call; without one the intake store fails and stores nothing. */
    fun submitter(): String =
        checkNotNull(tenants.currentActor()?.takeIf { it.isNotBlank() }) {
            "an intake is stored only with its submitting principal"
        }

    /**
     * A plain insert with no conflict clause: a second receipt of the operation and triple fails, and with it the
     * whole store, so no call adds data under the receipt of another (fail closed). `recorded_at` is the default.
     */
    fun record(
        operation: IntakeOperation,
        accepted: AcceptedIntake,
        submittedBy: String = submitter(),
    ) {
        dsl
            .insertInto(INTAKE_RECEIPT)
            .set(INTAKE_RECEIPT.TENANT_ID, tenants.current().value)
            .set(INTAKE_RECEIPT.INTAKE_OPERATION, operation.name)
            .set(INTAKE_RECEIPT.ENTITY_REF, accepted.entityRef)
            .set(INTAKE_RECEIPT.SOURCE_KEY, accepted.sourceKey)
            .set(INTAKE_RECEIPT.SOURCE_REVISION, accepted.sourceRevision)
            .set(INTAKE_RECEIPT.AGGREGATE_TYPE, accepted.aggregateType.name)
            .set(INTAKE_RECEIPT.SUBMITTED_BY, submittedBy)
            .execute()
    }
}
