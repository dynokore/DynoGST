package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.adapters.persistence.ConnectionRecord
import com.dynokore.gst.transport.adapters.persistence.JooqConnectionRecords
import com.dynokore.gst.transport.application.AdapterNotRegistered
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.SetTenantCeiling
import com.dynokore.gst.transport.contract.model.ApConnection
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.CeilingChange
import com.dynokore.gst.transport.domain.CeilingRefusal
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import java.math.BigDecimal

/**
 * `setThroughputCeiling` (INV-REQ-1317, tighten-only): the connection is read under the tenant predicate, so a ref of
 * another tenant is [ConnectionNotFound] (404 SRT-005) and its row is untouched. A ceiling above the agreed rate is
 * 422 POL-008; a connection with no agreed rate recorded is 422 POL-010 (bound unavailable, fail closed). A value of
 * zero or below is refused by the request schema before this runs. Nothing is stored in any refusal; run it inside the
 * transaction of the idempotency key record.
 */
class ThroughputCeilingCommand(
    private val ceilings: SetTenantCeiling,
    private val records: JooqConnectionRecords,
) {
    fun set(
        tenant: TenantRef,
        connectionRef: String,
        value: BigDecimal,
    ): ApConnection {
        val before = load(tenant, connectionRef)
        if (before.agreedRate == null) throw boundUnavailable()
        store(AdapterId(connectionRef), value)
        return load(tenant, connectionRef).toResponse()
    }

    private fun load(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionRecord = records.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)

    private fun store(
        adapter: AdapterId,
        value: BigDecimal,
    ) {
        val change =
            try {
                ceilings.set(adapter, value)
            } catch (_: AdapterNotRegistered) {
                throw boundUnavailable()
            }
        if (change is CeilingChange.Refused) throw refusal(change.reason)
    }

    private fun refusal(reason: CeilingRefusal): RuntimeException =
        when (reason) {
            CeilingRefusal.ABOVE_AGREED_RATE -> CatalogueRefusal(HTTP_UNPROCESSABLE, LOOSER, LOOSER_TITLE)
            CeilingRefusal.NOT_POSITIVE -> ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }

    private fun boundUnavailable() = CatalogueRefusal(HTTP_UNPROCESSABLE, BOUND_UNAVAILABLE, BOUND_TITLE)

    private fun ConnectionRecord.toResponse() =
        ApConnection(
            connectionRef = adapterRef,
            adapterRef = adapterRef,
            entityRefs = entityRefs.toSet(),
            version = version.toInt(),
            throughputCeiling = (tenantCeiling ?: agreedRate)?.plain(),
            agreedRate = agreedRate?.plain(),
        )

    /** The column keeps three decimals; the answer shows the number without trailing zeros and never as an exponent. */
    private fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private companion object {
        const val HTTP_UNPROCESSABLE = 422
        const val LOOSER = "POL-008"
        const val LOOSER_TITLE = "Looser than a broader scope"
        const val BOUND_UNAVAILABLE = "POL-010"
        const val BOUND_TITLE = "Bound unavailable"
    }
}
