package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionExportController
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionReconciliationController
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.boundary.BoundaryVersion
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.application.boundary.CheckSubmissionBoundary
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.replay.PreviewReplayScope
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.BoundaryCheckRequest
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-T-26433 (INV-AC-26430, INV-REQ-1005): the controller of `checkSubmissionBoundary` over the real use case. */
class CheckSubmissionBoundaryControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val mapper = jacksonObjectMapper()
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d3 = UUID.fromString("00000000-0000-0000-0000-0000000000d3")
    private val rows =
        listOf(
            BoundaryVersion(d1, 2, "E1", LifecycleState.SENT),
            BoundaryVersion(d3, 1, "E2", LifecycleState.READY),
        )

    private class Source(
        private val rows: List<BoundaryVersion>,
    ) : BoundaryVersionSource {
        var calls = 0

        override fun versions(
            tenant: TenantId,
            docUuids: Collection<UUID>,
        ): List<BoundaryVersion> {
            calls++
            return rows.filter { it.docUuid in docUuids }
        }
    }

    /** The replica holds no snapshot, so each decision goes to this fallback, which records it. */
    private class Decisions(
        private val denied: Set<String>,
    ) : AuthorityFallback {
        val requests = mutableListOf<AuthorizationRequest>()

        override fun fetch(
            key: AuthorizationRequest,
            deadline: Deadline,
        ): FallbackResult<AuthorityVerdict> {
            requests += key
            return FallbackResult.Found(
                AuthorityVerdict(key.action == "submission.view-status" && key.entityRef !in denied),
            )
        }
    }

    private class Fixture(
        val controller: TransactionsController,
        val source: Source,
        val decisions: Decisions,
    )

    @Suppress("LongMethod") // the composition of the controller delegates
    private fun fixture(
        caller: EndUserPrincipal? = user(),
        denied: Set<String> = emptySet(),
        unavailable: Boolean = false,
    ): Fixture {
        val source = Source(rows)
        val decisions = Decisions(denied)
        val roles = OperationRoleMap { emptyMap() }
        // A replica with no snapshot and no fallback answers AUTHORITY_UNAVAILABLE.
        val replica =
            if (unavailable) {
                AuthorityReplica(clock, roles)
            } else {
                AuthorityReplica(clock, roles, fallback = decisions)
            }
        val listed =
            object : SubmissionRowSource {
                override fun rows(
                    tenant: TenantId,
                    entityRef: String,
                    periodRef: String,
                ) = SourceRead(emptyList(), 0)
            }
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val controller =
            TransactionsController(
                ReplayBySelection(replica, { _, _ -> emptyList() }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                ReplaySingleVersion(replica, { _, _, _ -> null }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                clock,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
                SubmissionListingHandler(ListSubmissions(replica, listed), clock),
                SubmissionSummaryController(SubmissionSummaryQuery(replica, listed), clock),
                SubmissionReconciliationController(GetSubmissionReconciliation(replica, listed), clock),
                SubmissionExportController(
                    ExportSubmissions(replica, listed, EntityGstns { _, _ -> "M90000002B" }, clock),
                ),
                PreviewReplayScope(replica, { _, _ -> emptyList() }, { _, _, _ -> false }, NO_FACTS),
                CheckSubmissionBoundary(replica, source, 5000),
            )
        return Fixture(controller, source, decisions)
    }

    private fun user(tenant: TenantId? = this.tenant) = EndUserPrincipal("holder", tenant, emptySet(), true)

    /** Status and parsed JSON body; a refusal is rendered by the advice, as the framework does. */
    private fun call(
        f: Fixture,
        docs: List<UUID> = listOf(d1, d3),
    ): Pair<Int, JsonNode> {
        val outcome: Result<ResponseEntity<*>> =
            runCatching {
                CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
                    f.controller.checkSubmissionBoundary(BoundaryCheckRequest(docs))
                }
            }
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            val refusal = failure as? ComplianceRefusal ?: throw AssertionError("unexpected $failure")
            val response = TransactionsRefusalAdvice().refuse(refusal)
            return response.statusCode.value() to mapper.readTree(response.body!!)
        }
        val response = outcome.getOrThrow()
        return response.statusCode.value() to mapper.readTree(mapper.writeValueAsString(response.body))
    }

    /**
     * Whole-value equality of status and parsed problem body: the expected body is the library's rendering of [code]
     * under the answer's own `correlationId` (random per call), so any other member or value fails.
     */
    private fun shouldBeProblem(
        answer: Pair<Int, JsonNode>,
        status: Int,
        code: ClosedErrorCode,
        wire: String,
    ) {
        val (actual, body) = answer
        actual shouldBe status
        body["code"].asString() shouldBe wire
        body["type"].asString() shouldBe "https://errors.invoice.internal/$wire"
        body shouldBe mapper.readTree(Problems.closed(code, body["correlationId"].asString()).body)
    }

    @Test
    fun `INV-AC-26430 an allowed caller gets 200 with two checked and the one submitted member`() {
        val f = fixture()
        val (status, body) = call(f)
        status shouldBe 200
        body shouldBe
            mapper.readTree(
                """{"checkedCount":2,"submittedCount":1,"submittedMembers":[{"docUuid":"$d1","state":"SENT"}]}""",
            )
        f.decisions.requests.map { it.entityRef } shouldBe listOf("E1", "E2")
        f.decisions.requests
            .map { it.principal.id }
            .toSet() shouldBe setOf("holder")
        f.decisions.requests
            .map { it.tenant }
            .toSet() shouldBe setOf(tenant)
    }

    @Test
    fun `INV-AC-26430 a denied entity gets 403 SRT-004 with no count and no member`() {
        shouldBeProblem(call(fixture(denied = setOf("E2"))), 403, ClosedErrorCode.ACCESS_DENIED, "SRT-004")
    }

    @Test
    fun `INV-AC-26430 an unavailable replica gets the same 403 SRT-004`() {
        shouldBeProblem(call(fixture(unavailable = true)), 403, ClosedErrorCode.ACCESS_DENIED, "SRT-004")
    }

    @Test
    fun `INV-AC-26430 a caller without a tenant gets 401 and nothing is read`() {
        val f = fixture(caller = user(tenant = null))
        shouldBeProblem(call(f), 401, ClosedErrorCode.UNAUTHENTICATED, "SRT-003")
        f.source.calls shouldBe 0
        f.decisions.requests shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26430 too many documents get 413 SRT-014 and no read and no decision`() {
        val f = fixture()
        shouldBeProblem(call(f, (1L..5001L).map { UUID(0, it) }), 413, ClosedErrorCode.REQUEST_TOO_LARGE, "SRT-014")
        f.source.calls shouldBe 0
        f.decisions.requests shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26430 the controller method holds no permission string and no role name`() {
        val source =
            File("src/main/kotlin/com/dynokore/gst/submission/adapters/http/TransactionsController.kt").readText()
        val start = source.indexOf("override fun checkSubmissionBoundary(")
        check(start >= 0) { "the method is not in the source" }
        val end = source.indexOf("\n    }\n", start)
        check(end > start) { "the end of the method is not in the source" }
        val method = source.substring(start, end)
        method shouldNotContain "submission.view-status"
        method shouldNotContain "tax.operator"
    }
}
