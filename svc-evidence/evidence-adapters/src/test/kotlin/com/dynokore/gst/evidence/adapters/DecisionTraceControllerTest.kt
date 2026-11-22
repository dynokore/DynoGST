package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.LookupController
import com.dynokore.gst.evidence.adapters.http.LookupRefusal
import com.dynokore.gst.evidence.adapters.http.LookupRefusalAdvice
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.chain.AcknowledgementLocatorStore
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ChainRecordReader
import com.dynokore.gst.evidence.application.lookup.DOC_VIEW_CONTENT
import com.dynokore.gst.evidence.application.lookup.DecisionEntry
import com.dynokore.gst.evidence.application.lookup.DecisionTraceLookup
import com.dynokore.gst.evidence.application.lookup.DecisionTraceReader
import com.dynokore.gst.evidence.application.lookup.PackVersionRef
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.gst.evidence.application.lookup.StoredDecisionTrace
import com.dynokore.gst.evidence.application.lookup.SubmissionIndexReader
import com.dynokore.gst.evidence.application.lookup.TraceInput
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
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-20777: `getDecisionTrace` over HTTP; the use case is the one of INV-AC-20775 over a recording reader. */
class DecisionTraceControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val reads = mutableListOf<Int>()
    private val decisions =
        listOf(
            DecisionEntry(
                "RUL-001",
                "R1",
                listOf(PackVersionRef("pack-a", "1.2")),
                listOf(TraceInput("amount", "10", "IBT-123"), TraceInput("note", "x", null)),
            ),
        )

    private fun replica(granted: Boolean): AuthorityReplica {
        val roles =
            OperationRoleMap {
                if (granted && it == DOC_VIEW_CONTENT) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap()
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

    private val audit =
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

    private fun unused(): IntegrityStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IntegrityStore::class.java)) { _, _, _ ->
            error("not used")
        } as IntegrityStore

    private fun controller(
        caller: EndUserPrincipal?,
        stored: StoredDecisionTrace? = StoredDecisionTrace.Valid(at, decisions),
        granted: Boolean = true,
    ): LookupController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val locators =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(AcknowledgementLocatorStore::class.java),
            ) { _, _, _ -> error("not used") } as AcknowledgementLocatorStore
        val chain =
            AcknowledgementChainLookup(
                replica(false),
                locators,
                ChainRecordReader { _, _, _, _ -> error("not used") },
                IntegrityStatusQueryHandler(unused()),
                AuditedReads(audit),
            )
        return LookupController(
            chain,
            ReverseLookup(
                replica(false),
                SubmissionIndexReader { _, _, _, _ -> error("not used") },
                IntegrityStatusQueryHandler(unused()),
                AuditedReads(audit),
            ),
            DecisionTraceLookup(
                replica(granted),
                DecisionTraceReader { _, _, version ->
                    reads += version
                    stored
                },
            ),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
        )
    }

    private fun user(tenant: TenantId? = this.tenant) = EndUserPrincipal("user-42", tenant, emptySet())

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? LookupRefusal ?: throw AssertionError("expected LookupRefusal, got $thrown")
        val response = LookupRefusalAdvice().refuse(refusal)
        val code =
            Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
                .find(response.body!!)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return response.statusCode.value() to code
    }

    @Test
    fun `INV-AC-20777 a found trace answers 200 with the view and the decisions of the use case`() {
        val response = controller(user()).getDecisionTrace(doc, 2)
        response.statusCode.value() shouldBe 200
        val body = response.body!!
        body.docUuid shouldBe doc
        body.version shouldBe 2
        body.recordedAt.toInstant() shouldBe at
        body.decisions.size shouldBe 1
        val entry = body.decisions.single()
        entry.code shouldBe "RUL-001"
        entry.ruleId shouldBe "R1"
        entry.packVersions.map { it.packIdentifier to it.version } shouldBe listOf("pack-a" to "1.2")
        entry.inputs.map { Triple(it.name, it.value, it.businessTermId) } shouldBe
            listOf(Triple("amount", "10", "IBT-123"), Triple("note", "x", null))
        reads shouldBe listOf(2)
    }

    @Test
    fun `INV-AC-20777 no trace answers 404 SRT-005`() {
        val (status, code) = refused { controller(user(), stored = null).getDecisionTrace(doc, 1) }
        status shouldBe 404
        code shouldBe "SRT-005"
        (code == "SRT-0050") shouldBe false
    }

    @Test
    fun `INV-AC-20777 a malformed stored trace answers 500 SRT-010 and no decisions member`() {
        val thrown =
            runCatching { controller(user(), stored = StoredDecisionTrace.Malformed).getDecisionTrace(doc, 1) }
                .exceptionOrNull()
        val response = LookupRefusalAdvice().refuse(thrown as LookupRefusal)
        response.statusCode.value() shouldBe 500
        Regex("\"code\"\\s*:\\s*\"SRT-010\"").containsMatchIn(response.body!!) shouldBe true
        response.body!!.contains("decisions") shouldBe false
    }

    @Test
    fun `INV-AC-20777 a caller without the key answers 403 SRT-004 and no store is read`() {
        val (status, code) = refused { controller(user(), granted = false).getDecisionTrace(doc, 1) }
        status shouldBe 403
        code shouldBe "SRT-004"
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20777 a caller with no verified tenant answers 401 SRT-003`() {
        listOf(null, user(tenant = null)).forEach { caller ->
            val (status, code) = refused { controller(caller).getDecisionTrace(doc, 1) }
            status shouldBe 401
            code shouldBe "SRT-003"
        }
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20777 version 0 answers 400 SRT-001 and no store is read`() {
        val c = controller(user())
        val (status, code) = refused { c.getDecisionTrace(doc, 0) }
        status shouldBe 400
        code shouldBe "SRT-001"
        reads shouldBe emptyList()
        c.getDecisionTrace(doc, 1).statusCode.value() shouldBe 200
    }
}
