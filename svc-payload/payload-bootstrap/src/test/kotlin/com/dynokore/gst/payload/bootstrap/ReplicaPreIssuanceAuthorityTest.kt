package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.application.preissuance.AuthorizedCheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.AuthorizedPreIssuance
import com.dynokore.gst.payload.application.preissuance.CheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.PreIssuanceCaller
import com.dynokore.gst.payload.application.preissuance.RegisterLookup
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-27312: [ReplicaPreIssuanceAuthority] holds a permission only when the replica allows it from the source LOCAL
 * or FALLBACK. The replica is a fake that answers per request, so every source can be produced.
 */
class ReplicaPreIssuanceAuthorityTest {
    private val tenant = TenantId(UUID.nameUUIDFromBytes("tenant-a".toByteArray()))
    private val tenants =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantContext::class.java)) { _, method, _ ->
            // the mangled name of a function returning the value class TenantId: it returns the wrapped UUID
            check(method.name.startsWith("current-")) { "only current() is read, was ${method.name}" }
            tenant.value
        } as TenantContext

    private val seen = mutableListOf<AuthorizationRequest>()
    private val human = PreIssuanceCaller(Principal("u1", PrincipalKind.HUMAN))

    private fun authority(answer: (AuthorizationRequest) -> AuthorityDecision) =
        ReplicaPreIssuanceAuthority({ request, _: Deadline ->
            seen += request
            answer(request)
        }, tenants, Clock.systemUTC())

    private fun decision(
        allowed: Boolean,
        source: DecisionSource,
        reason: DecisionReason = if (allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT,
    ) = AuthorityDecision(allowed, source, reason)

    private val key = AuthorizedCheckPreIssuance.PERSON_PERMISSION

    @Test
    fun `INV-AC-27312 allowed from LOCAL holds`() {
        authority { decision(true, DecisionSource.LOCAL) }.holds(human, key, "E-001") shouldBe true
    }

    @Test
    fun `INV-AC-27312 allowed from FALLBACK holds`() {
        authority { decision(true, DecisionSource.FALLBACK) }.holds(human, key, "E-001") shouldBe true
    }

    @Test
    fun `INV-AC-27312 allowed from DEGRADED does not hold`() {
        authority {
            decision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
        }.holds(human, key, "E-001") shouldBe false
    }

    @Test
    fun `INV-AC-27312 denied does not hold`() {
        authority { decision(false, DecisionSource.LOCAL) }.holds(human, key, "E-001") shouldBe false
    }

    @Test
    fun `INV-AC-27312 a throwing replica does not hold and the use case answers Forbidden with no lookup`() {
        var lookups = 0
        val use =
            AuthorizedCheckPreIssuance(
                CheckPreIssuance(
                    RegisterStatusSource {
                        lookups++
                        RegisterLookup.Unavailable
                    },
                ),
                authority { error("replica down") },
            )
        use.execute(human, "M90054321K", "E-001", "SGD", LocalDate.of(2026, 3, 20), "TYPE_1A", "9.00") shouldBe
            AuthorizedPreIssuance.Forbidden
        lookups shouldBe 0
    }

    @Test
    fun `INV-AC-27312 the request carries the tenant, the permission, the entity and the principal`() {
        authority { decision(true, DecisionSource.LOCAL) }.holds(human, key, "E-001")
        val request = seen.single()
        request.tenant shouldBe tenant
        request.action shouldBe key
        request.entityRef shouldBe "E-001"
        request.principal.kind shouldBe PrincipalKind.HUMAN
        request.principal.id shouldBe "u1"
    }

    @Test
    fun `INV-AC-27312 a MACHINE caller is decided with its own verified principal and the permission it is given`() {
        val machine =
            MachinePrincipalFixtures.machine("m1", tenant, MachineEntityCoverage.Entity("E-001"), "INGESTION")
        authority { decision(true, DecisionSource.LOCAL) }
            .holds(PreIssuanceCaller(machine), AuthorizedCheckPreIssuance.MACHINE_PERMISSION, "E-001") shouldBe true
        val request = seen.single()
        (request.principal === machine) shouldBe true
        request.principal.kind shouldBe PrincipalKind.MACHINE
        request.action shouldBe "machine.ingestion.channels.write"
    }
}
