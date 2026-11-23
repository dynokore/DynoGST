package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.catalogue.Catalogue
import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.notification.syncedge.TestWorkloadVerifier
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

    private companion object {
        const val VP = "VP-67"

        // the tenant of TestWorkloadVerifier.testEndUserResolver
        const val TENANT = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e11"
    }
}
