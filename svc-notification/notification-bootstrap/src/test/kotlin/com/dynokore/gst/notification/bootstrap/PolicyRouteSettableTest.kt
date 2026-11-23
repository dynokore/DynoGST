package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.catalogue.Catalogue
import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.notification.syncedge.TestWorkloadVerifier
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-28106 (INV-T-28107): a tenant-bound request with no grant reaches the settable check of the policy route of
 * VP-67, which is planned, so the answer is 422 POL-006 and not 500 SRT-010 (the tenant predicate of the shared writer
 * needs the service-runtime config and idempotency tables in the schema of the service). The option name comes from the
 * generated catalogue row of VP-67; the principal is the stand-in end user of [TestWorkloadVerifier].
 */
class PolicyRouteSettableTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    private fun body(): String {
        val vp = checkNotNull(VariationPoint.fromId(VP)) { "$VP is not in the catalogue" }
        val option = Catalogue.optionKeys(vp).first { it != vp.default }
        val node = mapper.createObjectNode()
        node.put("scopeLevel", "TENANT")
        node.put("scopeRef", TENANT)
        val selected = node.putArray("selected").addObject()
        selected.put("option", option)
        selected.put("value", 48)
        node.put("effectiveDate", LocalDate.now().toString())
        node.put("reason", "retry window change while the variation point is planned")
        return mapper.writeValueAsString(node)
    }

    @Test
    fun `INV-AC-28106 a tenant-bound request with no grant gets 422 POL-006 from VP-67, not 500`(
        @TempDir dir: Path,
    ) {
        NotificationContext
            .startServlet(
                dir,
                listOf(TestWorkloadVerifier::class.java),
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                    "management.server.port=0",
                ),
                javaClass.classLoader,
            ).use { context ->
                val port = context.getBean(Environment::class.java).getProperty("local.server.port")
                grantSetter(context)
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/internal/v1/policies/$VP"))
                        .header("X-Workload-Token", "token-console-bff")
                        .header("Authorization", "Bearer end-user-p1")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body()))
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                println("INV-T-28107 answer: ${response.statusCode()} ${response.body()}")
                response.statusCode() shouldBe 422
                mapper.readTree(response.body()).get("code")?.asString() shouldBe "POL-006"
            }
    }

    /**
     * The shared setPolicy is decided by the permission key `vp-67.set` through the authority replica (service-runtime
     * 12.0.0, INV-Q-10027): the stand-in end user holds a tenant-scoped grant of a role the generated operation-role
     * map lists for that key, so the request passes the access check and reaches the settable check (INV-T-27032 owns
     * this adaptation of the BOM bump, INV-Q-16901).
     */
    private fun grantSetter(context: org.springframework.context.ApplicationContext) {
        val role =
            context
                .getBean(OperationRoleMap::class.java)
                .rolesFor(VP.lowercase() + ".set")
                .entries
                .first { it.value == CellAllowance.PERMITTED }
                .key
        val replica = context.getBean(AuthorityReplica::class.java)
        replica.onSnapshotComplete(0)
        replica.accept(
            AuthorityChanged(
                TenantId.of(TENANT),
                "end-user-1",
                1L,
                AuthorityRecord.RoleGrantRecord(
                    "g-end-user-1-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.TENANT, TENANT, Instant.now().minusSeconds(60)),
                ),
            ),
        )
    }

    private companion object {
        const val VP = "VP-67"

        // the tenant of TestWorkloadVerifier.testEndUserResolver
        const val TENANT = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e11"
    }
}
