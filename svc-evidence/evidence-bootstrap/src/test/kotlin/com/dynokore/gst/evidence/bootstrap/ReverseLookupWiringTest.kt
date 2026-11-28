package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.ChainPageCursor
import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.http.LookupRefusalAdvice
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.ENQUIRY_LOOKUP_REVERSE
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
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
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20724: the `reverseLookup` bean of [LookupConfiguration] composes the controller and reads the index. */
class ReverseLookupWiringTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val configuration = LookupConfiguration()
    private val sql = mutableListOf<String>()
    private val bound = mutableListOf<List<Any?>>()

    private val dsl =
        DSL.using(
            MockConnection(
                MockDataProvider { ctx ->
                    sql += ctx.sql()
                    bound += ctx.bindings().toList()
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

    private fun entityGrant(entity: String) =
        RoleGrant("auditor", GrantScope.ENTITY, entity, Instant.parse("2026-01-01T00:00:00Z"), null)

    private fun replica(grant: RoleGrant?): AuthorityReplica {
        val roles =
            OperationRoleMap {
                if (it ==
                    ENQUIRY_LOOKUP_REVERSE
                ) {
                    mapOf("auditor" to CellAllowance.PERMITTED)
                } else {
                    emptyMap()
                }
            }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        grant?.let {
            replica.accept(
                AuthorityChanged(
                    tenant,
                    "user-42",
                    1,
                    AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, it),
                ),
            )
        }
        replica.onSnapshotComplete(1)
        replica.observeHead(1)
        return replica
    }

    /** The mock database has no tenant transaction: the runner runs the block as it is. */
    private val noTransaction =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    /** The controller composed from the beans of [LookupConfiguration] over [replica]. */
    private fun controller(replica: AuthorityReplica): LookupController {
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
        return LookupController(
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
            ChainPageCursor(MountedFileSecretStore(Path.of("not-used"))),
            clock,
        )
    }

    private fun LookupController.search() = reverseLookup(null, null, null, null, null, null, null, 100, null)

    @Test
    fun `INV-AC-20724 the controller is built with the reverse lookup bean and serves the operation`() {
        val response = controller(replica(entityGrant("E1"))).search()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.items).isEmpty()
        assertThat(sql).hasSize(1)
        assertThat(sql.single()).contains("submission_index")
    }

    @Test
    fun `INV-AC-1065 the wired operation binds the caller's entity into the submission index statement`() {
        val response = controller(replica(entityGrant("E1"))).search()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(sql).hasSize(1)
        assertThat(sql.single()).contains("submission_index")
        // entity_ref is also selected, so the predicate is looked for after `where`
        assertThat(sql.single().substringAfter(" where ")).contains("\"entity_ref\" in (?)")
        assertThat(bound.single()).contains("E1")
    }

    @Test
    fun `INV-AC-1065 a caller with no entity grant is 403 and no statement is recorded`() {
        val tenantGrant =
            RoleGrant(
                "auditor",
                GrantScope.TENANT,
                tenant.value.toString(),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
            )
        // no grant at all, and a tenant-scope grant with no structure replica to name its entities
        listOf(null, tenantGrant).forEach { grant ->
            val thrown = runCatching { controller(replica(grant)).search() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(LookupRefusal::class.java)
            val refusal = thrown as LookupRefusal
            assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(
                LookupRefusalAdvice()
                    .refuse(refusal)
                    .statusCode
                    .value(),
            ).isEqualTo(403)
        }
        assertThat(sql).isEmpty()
    }
}
