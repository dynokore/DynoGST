package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.DOC_VIEW_CONTENT
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20777: the `decisionTraceLookup` bean of [LookupConfiguration] composes the controller. */
class DecisionTraceWiringTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val configuration = LookupConfiguration()
    private val sql = mutableListOf<String>()

    private val dsl =
        DSL.using(
            MockConnection(
                MockDataProvider { ctx ->
                    sql += ctx.sql()
                    arrayOf(MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult()))
                },
            ),
            SQLDialect.POSTGRES,
        )

    private val operations =
        object : AuditedOperations {
            override fun <T> change(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("not used")

            override fun <T> read(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = block()
        }

    private fun replica(): AuthorityReplica {
        val roles =
            OperationRoleMap {
                if (it == DOC_VIEW_CONTENT) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap()
            }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        val grant =
            RoleGrant(
                "auditor",
                GrantScope.TENANT,
                tenant.value.toString(),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
            )
        replica.accept(
            AuthorityChanged(tenant, "user-42", 1, AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant)),
        )
        replica.onSnapshotComplete(1)
        replica.observeHead(1)
        return replica
    }

    /** The mock database has no tenant transaction: the runner runs the block as it is. */
    private val noTransaction =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    @Test
    fun `INV-AC-20777 the controller is built with the decision trace bean and reads the trace table`() {
        val replica = replica()
        val audited = configuration.auditedReads(operations)
        val unusedStore =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IntegrityStore::class.java)) { _, _, _ ->
                error("not used")
            } as IntegrityStore
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute")
                EndUserPrincipal("user-42", tenant, emptySet()).takeIf {
                    args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE
                }
            } as HttpServletRequest
        val controller =
            LookupController(
                configuration.acknowledgementChainLookup(
                    replica,
                    dsl,
                    IntegrityStatusQueryHandler(unusedStore),
                    audited,
                ),
                configuration.reverseLookup(replica, dsl, IntegrityStatusQueryHandler(unusedStore), audited),
                configuration.decisionTraceLookup(replica, dsl),
                configuration.transmittedVersionLookup(replica, dsl, noTransaction),
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
            )
        // the mock store holds no trace: NotFound, the 404 refusal, after one read of the trace table
        assertThatThrownBy { controller.getDecisionTrace(UUID.randomUUID(), 1) }
            .hasMessage("SRT-005")
        assertThat(sql).hasSize(1)
        assertThat(sql.single()).contains("decision_trace")
    }
}
