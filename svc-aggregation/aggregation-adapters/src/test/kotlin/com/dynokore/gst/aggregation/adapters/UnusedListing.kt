package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.http.IntakeListingCursors
import com.dynokore.gst.aggregation.adapters.http.IntakeListingEndpoint
import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.IntakeListingReader
import com.dynokore.gst.aggregation.application.ListSubmittedIntake
import com.dynokore.platform.serviceruntime.binding.Secret
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

/** For a test of the intake operations of the controller: a listing that fails when it is used. */
fun unusedListing(): IntakeListingEndpoint {
    val unused = "listAggregationIntake is not under test here"
    val reader =
        object : IntakeListingReader {
            override fun entityRefs(filter: IntakeListingFilter): Set<String> = error(unused)

            override fun page(
                filter: IntakeListingFilter,
                permittedEntityRefs: Set<String>,
                after: IntakeListingPosition?,
                limit: Int,
            ) = error(unused)
        }
    val tenants =
        object : TenantContext {
            override fun current(): TenantId = error(unused)

            override fun currentActor(): String = error(unused)

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = error(unused)
        }
    val secrets =
        object : SecretStore {
            override fun read(ref: SecretRef): Secret = error(unused)
        }
    return IntakeListingEndpoint(
        ListSubmittedIntake(reader),
        { _, _ -> error(unused) },
        IntakeListingCursors(secrets, Clock.systemUTC()),
        tenants,
    )
}
