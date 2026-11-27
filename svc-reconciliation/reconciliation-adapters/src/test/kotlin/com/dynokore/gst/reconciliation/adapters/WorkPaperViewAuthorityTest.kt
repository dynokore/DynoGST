package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.reconciliation.adapters.http.AttestAuthority
import com.dynokore.gst.reconciliation.adapters.http.AttestationRefusal
import com.dynokore.gst.reconciliation.adapters.http.AttestationRefusalAdvice
import com.dynokore.gst.reconciliation.adapters.http.AuthorityReplicaWorkPaperViewAuthority
import com.dynokore.gst.reconciliation.adapters.http.ExportAuthority
import com.dynokore.gst.reconciliation.adapters.http.RunsController
import com.dynokore.gst.reconciliation.adapters.http.WORK_PAPER_VIEW_PERMISSION
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunItems
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.gst.reconciliation.application.workpaper.AttestWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestationWriter
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.RunPreparers
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SealedRef
import com.dynokore.gst.reconciliation.application.workpaper.SealedWorkPapers
import com.dynokore.gst.reconciliation.application.workpaper.SingleAttestationPolicy
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperSource
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.export.RelaxedControlsSource
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.WorkPaper
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.json.JsonParserFactory
import org.springframework.http.ResponseEntity
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-28548 (INV-T-28549): `getWorkPaper` of [RunsController] over the replica authority and fakes only. */
@Suppress("TooManyFunctions", "LargeClass")
class WorkPaperViewAuthorityTest {
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val tenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val r1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

    /** The permission keys held, each for one entity of the tenant; the fake replica allows exactly these. */
    private var grants = setOf("work-paper.view" to "E1")
    private var answer: ((AuthorizationRequest) -> AuthorityDecision)? = null
    private val asked = mutableListOf<AuthorizationRequest>()

    /** The events of one call in order: "read" (run store), "decide" and "use-case". */
    private val events = mutableListOf<String>()

    /** Calls of the use case [GetWorkPaper]: only it reads the annotations of a run. */
    private var useCaseCalls = 0

    private val snapshot =
        RunSnapshot(
            RecordedRun(r1.toString(), "E1", "P1", 2, now, "F5"),
            listOf(
                RunBox(
                    "1",
                    BigDecimal("100.00"),
                    BigDecimal("90.00"),
                    BigDecimal("10.00"),
                    emptyList(),
                    BigDecimal("10.00"),
                ),
            ),
            BigDecimal("10.00"),
            emptyList(),
            false,
        )

    private val reader =
        object : RunReader {
            override fun latest(
                tenant: String,
                entityRef: String,
                periodRef: String,
            ): RunSnapshot? = null

            override fun page(
                tenant: String,
                entityRef: String,
                periodRef: String,
                limit: Int,
                after: RunCursor?,
            ): List<RecordedRun> = emptyList()

            override fun find(
                tenant: String,
                runId: String,
            ): RunSnapshot? {
                events += "read"
                return snapshot.takeIf { tenant == tenantId.toString() && runId == r1.toString() }
            }
        }

    private val countingAnnotations =
        object : RunAnnotations {
            override fun latest(
                tenant: String,
                runId: String,
                box: String,
            ): StoredAnnotation? = null

            override fun all(
                tenant: String,
                runId: String,
            ): List<StoredAnnotation> {
                useCaseCalls++
                events += "use-case"
                return emptyList()
            }

            override fun insert(
                tenant: String,
                stored: StoredAnnotation,
            ): Boolean = false
        }

    private val attestations = RunAttestations { _, _ -> null }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenantId

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun decision(
        allowed: Boolean,
        source: DecisionSource,
        reason: DecisionReason = if (allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT,
    ) = AuthorityDecision(allowed, source, reason)

    private val authority =
        AuthorityReplicaWorkPaperViewAuthority(
            { request, _ ->
                asked += request
                events += "decide"
                answer?.invoke(request)
                    ?: decision((request.action to request.entityRef) in grants, DecisionSource.LOCAL)
            },
            tenants,
        )

    private fun neverSeals() =
        SealWorkPaper(
            WorkPaperSource { _, _ -> null },
            object : SealedWorkPapers {
                override fun latest(
                    tenant: String,
                    runId: String,
                ): SealedRef? = null

                override fun put(
                    tenant: String,
                    entityRef: String,
                    runId: String,
                    sealVersion: Int,
                    bytes: ByteArray,
                ): String = error("this fixture never seals")
            },
            ArtefactRecordedPublisher { _, _, _, _, _ -> error("this fixture never seals") },
        )

    private val neverAudits =
        object : AuditedOperations {
            override fun <T> change(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("this fixture never audits")

            override fun <T> read(
                entry: AuditEntry,
                context: EventContext,
                block: () -> T,
            ): T = error("this fixture never audits")
        }

    private fun principal(
        entities: Set<String> = setOf("E1"),
        current: Boolean = true,
    ) = EndUserPrincipal("U7", tenantId, entities.map(::EntityRef).toSet(), current)

    private fun controller(principal: EndUserPrincipal?): RunsController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                val asked = method.name == "getAttribute"
                principal.takeIf { asked && args?.get(0) == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        val beans = DefaultListableBeanFactory().apply { registerSingleton("request", request) }
        val useCase = GetWorkPaper(reader, countingAnnotations, attestations)
        return RunsController(
            ListRuns(reader),
            GetRun(reader),
            useCase,
            runner,
            tenants,
            AttestWorkPaper(
                reader,
                useCase,
                RunPreparers { _, _ -> null },
                SingleAttestationPolicy { _, _, _ -> null },
                attestations,
                RunAttestationWriter { _, _, _ -> error("this fixture never attests") },
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            neverSeals(),
            AttestAuthority { _, _, _ -> error("this fixture never attests") },
            beans.getBeanProvider(HttpServletRequest::class.java),
            ExportWorkPaper(
                useCase,
                countingAnnotations,
                RunItems { _, _ -> error("this fixture never exports") },
                RelaxedControlsSource { _, _, _ -> error("this fixture never exports") },
                WorkPaperRenderer { _, _ -> error("this fixture never exports") },
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            ExportAuthority { _, _, _ -> error("this fixture never exports") },
            neverAudits,
            authority,
        )
    }

    /** The response of one call; a thrown refusal is rendered by the production advice. */
    private fun call(
        run: UUID = r1,
        principal: EndUserPrincipal? = principal(),
        deadlineBound: Boolean = true,
    ): ResponseEntity<*> {
        val invoke: () -> ResponseEntity<*> = {
            try {
                controller(principal).getWorkPaper(run)
            } catch (refusal: AttestationRefusal) {
                AttestationRefusalAdvice().refuse(refusal)
            }
        }
        if (!deadlineBound) return invoke()
        return CurrentDeadline.callWith(
            Deadline.after(Duration.ofSeconds(2), Clock.fixed(now, ZoneOffset.UTC)),
            invoke,
        )
    }

    /** A 403 SRT-004 envelope with no work-paper field, and the use case was not called. */
    private fun assertRefused(response: ResponseEntity<*>) {
        response.statusCode.value() shouldBe 403
        val text = response.body as String
        val body = JsonParserFactory.getJsonParser().parseMap(text)
        body["code"] shouldBe "SRT-004"
        ClosedErrorCode.ACCESS_DENIED.code shouldBe "SRT-004"
        WORK_PAPER_FIELDS.filter { it in body.keys } shouldBe emptyList()
        listOf(r1.toString(), "E1", "P1", "100.00", "90.00").forEach { text shouldNotContain "\"$it\"" }
        useCaseCalls shouldBe 0
        events shouldBe listOf("read", "decide")
    }

    @Test
    fun `INV-AC-28548 an allow of the key for the run's entity returns the work-paper after one decision`() {
        val response = call()
        response.statusCode.value() shouldBe 200
        val paper = response.body as WorkPaper
        paper.runId shouldBe r1
        paper.header.entityRef shouldBe "E1"
        paper.boxes.map { it.box } shouldBe listOf("1")
        useCaseCalls shouldBe 1
        events shouldBe listOf("read", "decide", "read", "use-case")
        asked.single().let {
            it.tenant shouldBe tenantId
            it.principal.id shouldBe "U7"
            it.principal.kind shouldBe PrincipalKind.HUMAN
            it.entityRef shouldBe "E1"
            it.action shouldBe "work-paper.view"
        }
    }

    @Test
    fun `INV-AC-28548 a FALLBACK allow also returns the work-paper`() {
        answer = { decision(true, DecisionSource.FALLBACK) }
        call().statusCode.value() shouldBe 200
        useCaseCalls shouldBe 1
    }

    @Test
    fun `INV-AC-28548 a deny refuses with 403 SRT-004 and the use case is not called`() {
        grants = emptySet()
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 a fallback deny refuses`() {
        answer = { decision(false, DecisionSource.FALLBACK) }
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 an unavailable answer refuses`() {
        answer = { decision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE) }
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 a stale answer refuses even when it says allowed`() {
        answer = { decision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES) }
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 an exception from the replica refuses`() {
        answer = { error("replica failed") }
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 a checked exception from the replica refuses`() {
        answer = { throw java.io.IOException("replica unreachable") }
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 a call with no bound deadline asks nothing and refuses`() {
        val response = call(deadlineBound = false)
        response.statusCode.value() shouldBe 403
        asked shouldBe emptyList()
        useCaseCalls shouldBe 0
    }

    @Test
    fun `INV-AC-28548 look-alike a caller allowed only reconciliation_export is refused`() {
        grants = setOf("reconciliation.export" to "E1")
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 look-alike a caller allowed only work-paper_views is refused`() {
        grants = setOf("work-paper.views" to "E1")
        assertRefused(call())
    }

    @Test
    fun `INV-AC-28548 look-alike the key for another entity of the same tenant is refused`() {
        grants = setOf("work-paper.view" to "E2")
        assertRefused(call(principal = principal(setOf("E1", "E2"))))
    }

    @Test
    fun `INV-AC-28548 no end-user principal is 401 and nothing is read or decided`() {
        val response = call(principal = null)
        response.statusCode.value() shouldBe 401
        events shouldBe emptyList()
        useCaseCalls shouldBe 0
    }

    @Test
    fun `INV-AC-28548 an unknown run is 404 with no decision`() {
        val response = call(run = unknown)
        response.statusCode.value() shouldBe 404
        (response.body as ErrorEnvelope).code shouldBe ClosedErrorCode.NOT_FOUND.code
        events shouldBe listOf("read")
        asked shouldBe emptyList()
        useCaseCalls shouldBe 0
    }

    @Test
    fun `INV-AC-28548 an entity outside the current entity set is 404 with no decision`() {
        val response = call(principal = principal(setOf("E9")))
        response.statusCode.value() shouldBe 404
        (response.body as ErrorEnvelope).code shouldBe ClosedErrorCode.NOT_FOUND.code
        events shouldBe listOf("read")
        asked shouldBe emptyList()
        useCaseCalls shouldBe 0
    }

    @Test
    fun `INV-AC-28548 with a set that is not current the decision alone rules`() {
        grants = emptySet()
        assertRefused(call(principal = principal(setOf("E9"), current = false)))
    }

    @Test
    fun `INV-AC-28548 the pinned catalogue lists roles for the key in every form and none for the look-alike`() {
        DeploymentForm.entries.forEach { form ->
            OperationRoles.rolesFor(form, WORK_PAPER_VIEW_PERMISSION).isNotEmpty() shouldBe true
            OperationRoles.rolesFor(form, "work-paper.views").isEmpty() shouldBe true
        }
        WORK_PAPER_VIEW_PERMISSION shouldBe "work-paper.view"
    }

    private companion object {
        /** Every top-level field of the generated [WorkPaper] model. */
        val WORK_PAPER_FIELDS =
            WorkPaper::class.java.declaredFields
                .map { it.name }
                .filter { it != "Companion" }
    }
}
