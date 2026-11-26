package com.dynokore.gst.submission.acceptance

import com.dynokore.gst.submission.adapters.http.SubmissionExportController
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionReconciliationController
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.application.boundary.CheckSubmissionBoundary
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.application.replay.PreviewReplayScope
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.serviceruntime.http.BindingFailureAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC)
private val TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))

/** Records each read of the boundary use case: a request that reaches the controller with valid items is one read. */
class RecordingBoundarySource : BoundaryVersionSource {
    val reads = mutableListOf<Int>()

    override fun versions(
        tenant: TenantId,
        docUuids: Collection<UUID>,
    ) = emptyList<com.dynokore.gst.submission.application.boundary.BoundaryVersion>().also {
        reads += docUuids.size
    }
}

/**
 * The generated interface served by [TransactionsController] with fake use cases, its refusal advice and the library
 * auto-configuration that holds the 413 mapping. The first filter stands for the identity and deadline filters.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(
    TomcatServletWebServerAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    WebMvcAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    JacksonAutoConfiguration::class,
    BindingFailureAutoConfiguration::class,
)
@Import(TransactionsRefusalAdvice::class)
class BoundaryServletApplication {
    @Bean
    fun source() = RecordingBoundarySource()

    @Bean
    fun callContext(): Filter =
        Filter { request, response, chain ->
            request.setAttribute(
                EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
                EndUserPrincipal("holder", TENANT, emptySet(), true),
            )
            CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), CLOCK)) {
                chain.doFilter(request, response)
            }
        }

    @Suppress("LongMethod") // the composition of the controller delegates
    @Bean
    fun transactionsController(
        requests: ObjectProvider<HttpServletRequest>,
        source: RecordingBoundarySource,
    ): TransactionsController {
        val replica = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })
        val rows =
            object : SubmissionRowSource {
                override fun rows(
                    tenant: TenantId,
                    entityRef: String,
                    periodRef: String,
                ) = SourceRead(emptyList(), 0)
            }
        val facts =
            object : RegisterListFactsStore {
                override fun store(
                    tenant: TenantId,
                    docUuid: String,
                    facts: ListFacts,
                ): Boolean = throw UnsupportedOperationException()

                override fun facts(
                    tenant: TenantId,
                    docUuid: String,
                ): ListFacts? = null
            }
        return TransactionsController(
            ReplayBySelection(replica, { _, _ -> emptyList() }, { _, _, _ -> RequeueOutcome.REQUEUED }),
            ReplaySingleVersion(replica, { _, _, _ -> null }, { _, _, _ -> RequeueOutcome.REQUEUED }),
            CLOCK,
            requests,
            SubmissionListingHandler(ListSubmissions(replica, rows), CLOCK),
            SubmissionSummaryController(SubmissionSummaryQuery(replica, rows), CLOCK),
            SubmissionReconciliationController(GetSubmissionReconciliation(replica, rows), CLOCK),
            SubmissionExportController(ExportSubmissions(replica, rows, EntityGstns { _, _ -> "M90000002B" }, CLOCK)),
            PreviewReplayScope(replica, { _, _ -> emptyList() }, { _, _, _ -> false }, facts),
            // The cap is far above the contract's 5000, so any request that reaches the use case reads the source.
            CheckSubmissionBoundary(replica, source, Int.MAX_VALUE),
        )
    }
}

/** INV-T-26433 (INV-AC-26430): real HTTP requests to `checkSubmissionBoundary` on an embedded servlet. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckSubmissionBoundaryServletTest {
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val path = "/internal/v1/submission/boundary-checks"

    // spring.config.name: the application.yaml of submission-bootstrap on the classpath is not this test's.
    private val context =
        SpringApplicationBuilder(BoundaryServletApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .run("--server.port=0", "--spring.main.banner-mode=off", "--spring.config.name=boundary-servlet-test")
    private val port = checkNotNull((context as WebServerApplicationContext).webServer).port
    private val source = context.getBean(RecordingBoundarySource::class.java)

    @BeforeEach
    fun reset() = source.reads.clear()

    @AfterAll
    fun stop() = context.close()

    private fun post(
        count: Int,
        to: String = path,
    ): HttpResponse<String> {
        val items = (1..count).joinToString(",") { "\"${UUID(0, it.toLong())}\"" }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$to"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"docUuids":[$items]}"""))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `INV-AC-26430 5001 Doc UUIDs answer 413 SRT-014 and the use case is not called`() {
        val response = post(5001)
        response.statusCode() shouldBe 413
        mapper.readTree(response.body())["code"].asString() shouldBe "SRT-014"
        source.reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26430 5000 Doc UUIDs pass the validation and reach the use case`() {
        val response = post(5000)
        response.statusCode() shouldBe 200
        mapper.readTree(response.body()) shouldBe
            mapper.readTree("""{"checkedCount":5000,"submittedCount":0,"submittedMembers":[]}""")
        source.reads shouldBe listOf(5000)
    }

    @Test
    fun `INV-AC-26430 an empty docUuids answers 400 SRT-001`() {
        val response = post(0)
        response.statusCode() shouldBe 400
        mapper.readTree(response.body())["code"].asString() shouldBe "SRT-001"
        source.reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26430 the look-alike path answers 404 and never 413`() {
        val response = post(5001, "/internal/v1/submission/boundary-check")
        response.statusCode() shouldBe 404
        response.statusCode() shouldNotBe 413
        source.reads shouldBe emptyList()
    }
}
