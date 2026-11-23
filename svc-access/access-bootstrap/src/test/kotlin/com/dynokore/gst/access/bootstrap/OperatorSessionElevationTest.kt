package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.DecideElevationRequest
import com.dynokore.gst.access.contract.model.ElevationRequest
import com.dynokore.gst.access.contract.model.OperatorSessionResponse
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * INV-T-27477 (INV-REQ-862 b3-b4, INV-AC-27476): the operator access record of a session carries the elevation record
 * exactly when `doc.view.content` is allowed to the session. The fixture is that of [OperatorSessionTest].
 */
class OperatorSessionElevationTest {
    private val fixture = OperatorSessionTest()

    /** Requests an elevation of [actor] for [tenant] and returns its reference, undecided. */
    private fun requestFor(
        env: OperatorSessionTest.Env,
        actor: String,
        tenant: TenantId = env.a,
    ): String =
        env.tenants.bind(tenant, actor) {
            env.api
                .requestElevation(UUID.randomUUID(), ElevationRequest("CASE-1", tenant.toString(), "x"))
                .body!!
                .requestRef
        }

    private fun decide(
        env: OperatorSessionTest.Env,
        ref: String,
        decision: DecideElevationRequest.Decision,
        tenant: TenantId = env.a,
    ) = env.tenants.bind(tenant, env.officer) {
        env.api.decideElevation(ref, UUID.randomUUID(), DecideElevationRequest(decision, 30))
    }

    /** Opens a session of [operator] and checks the three facts of INV-AC-27476 against [present]. */
    private fun assertRecord(
        env: OperatorSessionTest.Env,
        operator: String,
        present: Boolean,
        tenantRef: String = env.a.toString(),
    ) {
        val opened = env.open(operator, tenantRef)
        val record =
            fixture
                .accessRecords(env, operator)
                .last()
                .data["operatorAccessRecord"]
        assertThat(record.has("elevation")).isEqualTo(present)
        assertThat(record["permissions"].toList().map { it.asString() }).doesNotContain("doc.view.content")
        assertThat(opened.contentMode).isEqualTo(
            if (present) OperatorSessionResponse.ContentMode.ELEVATED else OperatorSessionResponse.ContentMode.MASKED,
        )
    }

    @Test
    fun `INV-AC-27476 the elevation record is present for an approved unexpired elevation`() =
        fixture.run { env, _ ->
            env.elevate(env.support)
            assertRecord(env, env.support, present = true)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent with no elevation`() =
        fixture.run { env, _ -> assertRecord(env, env.support, present = false) }

    @Test
    fun `INV-AC-27476 the elevation record is absent before approval`() =
        fixture.run { env, _ ->
            requestFor(env, env.support)
            assertRecord(env, env.support, present = false)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent after refusal`() =
        fixture.run { env, _ ->
            decide(env, requestFor(env, env.support), DecideElevationRequest.Decision.REFUSE)
            assertRecord(env, env.support, present = false)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent after withdrawal`() =
        fixture.run { env, _ ->
            val ref = requestFor(env, env.support)
            env.tenants.bind(env.a, env.support) { env.api.withdrawElevationRequest(ref, UUID.randomUUID()) }
            assertRecord(env, env.support, present = false)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent after expiry`() =
        fixture.run { env, _ ->
            env.elevate(env.support)
            assertRecord(env, env.support, present = true)
            env.clock.advance(Duration.ofMinutes(31))
            assertRecord(env, env.support, present = false)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent for an elevation of another tenant`() =
        fixture.run { env, _ ->
            val other = TenantId(UUID.randomUUID())
            env.structure(other, StructureNodeKind.TENANT, retired = false)
            Seeds.grant(env.context, other, SeedSpec(env.officer, Role.SECURITY_OFFICER))
            decide(env, requestFor(env, env.support, other), DecideElevationRequest.Decision.GRANT, other)
            assertRecord(env, env.support, present = false)
        }

    @Test
    fun `INV-AC-27476 the elevation record is absent for an elevation of another operator`() =
        fixture.run { env, _ ->
            env.elevate(env.support2)
            assertRecord(env, env.support, present = false)
            assertRecord(env, env.support2, present = true)
        }

    companion object {
        @BeforeAll
        @JvmStatic
        fun baselineBegin() = OperatorSessionTest.baselineBegin()

        /** The platform rows the fixture inserts are removed exactly as `OperatorSessionTest` does. */
        @AfterAll
        @JvmStatic
        fun removePlatformRows() = OperatorSessionTest.removePlatformRows()
    }
}
