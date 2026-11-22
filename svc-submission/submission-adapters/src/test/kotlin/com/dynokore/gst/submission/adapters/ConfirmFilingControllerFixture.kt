package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.FilingAccessRefusal
import com.dynokore.gst.submission.adapters.http.FilingAccessRefusalAdvice
import com.dynokore.gst.submission.adapters.http.FilingPeriodRefusal
import com.dynokore.gst.submission.adapters.http.FilingRefusalAdvice
import com.dynokore.gst.submission.adapters.http.ReadinessController
import com.dynokore.gst.submission.adapters.http.ReadinessRefusalAdvice
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.ConfirmFilingActions
import com.dynokore.gst.submission.application.filing.FilerOptionsAt
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.filing.FilingFact
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.application.filing.NewFilingFact
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.contract.model.ConfirmFilingRequest
import com.dynokore.gst.submission.domain.filer.FilerOption
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** In-memory fixture of the `confirmFiling` controller tests: the real use case over in-memory ports. */
open class ConfirmFilingControllerFixture {
    protected val clock = Clock.fixed(Instant.parse("2026-04-10T00:00:00Z"), ZoneOffset.UTC)
    protected val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    protected val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    protected val zone = ZoneId.of("Asia/Singapore")
    protected val mapper = jacksonObjectMapper()

    protected val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** The stored facts; an identical fact is appended once; `recordedAt` is the store's stamp. */
    protected class Store : FilingFactStore {
        val stored = mutableListOf<FilingFact>()
        val recordedBy get() = stored.map { it.recordedBy }

        override fun facts(
            entityRef: String,
            periodRef: String,
        ) = stored.filter { it.entityRef == entityRef && it.periodRef == periodRef }

        override fun append(fact: NewFilingFact) {
            val same =
                stored.any {
                    it.entityRef == fact.entityRef && it.periodRef == fact.periodRef && it.filedAt == fact.filedAt &&
                        it.acknowledgementRef == fact.acknowledgementRef && it.correction == fact.correction
                }
            if (!same) {
                stored +=
                    FilingFact(
                        fact.entityRef,
                        fact.periodRef,
                        fact.filedAt,
                        fact.acknowledgementRef,
                        fact.portalRef,
                        fact.correction,
                        fact.recordedBy,
                        Instant.parse("2026-04-18T08:00:00Z").plusSeconds(stored.size.toLong()),
                    )
            }
        }
    }

    protected val store = Store()
    protected val notReady = LatestCheck("chk-8", CheckVerdict.NOT_READY)
    protected val ready = LatestCheck("chk-7", CheckVerdict.READY)
    protected var transactionEntries = 0
    protected val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                transactionEntries++
                return block()
            }
        }
    protected val dueDates =
        FilingDueDates { _, period ->
            when (period) {
                "P2" -> null
                "P3" -> throw AmbiguousDueDateException("two dates")
                else -> LocalDate.of(2026, 4, 30)
            }
        }
    protected val latest =
        object : LatestChecks {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ) = if (periodRef == "P4") notReady else ready

            override fun knownCheckIds(
                entityRef: String,
                periodRef: String,
            ): Set<String> = emptySet()
        }

    protected val agentGrantRole =
        roleMap
            .rolesFor(ConfirmFilingActions.PERSON)
            .keys
            .sorted()
            .first()

    protected var consentVersion = 10L

    /** The live consent of the client entity "E" listing exactly [items]; the agent holds a CONSENT-scope grant. */
    protected fun consent(vararg items: ConsentScopeItem) {
        replica.accept(
            AuthorityChanged(
                tenant,
                "agent1",
                consentVersion++,
                AuthorityRecord.ConsentRecord(
                    "E",
                    ConsentState.LIVE,
                    items.toSet(),
                    clock.instant().minusSeconds(60),
                ),
            ),
        )
    }

    protected val replica = replica()

    protected fun replica(): AuthorityReplica {
        val r = AuthorityReplica(clock, roleMap)
        r.onSnapshotComplete(0)
        val role =
            roleMap
                .rolesFor(ConfirmFilingActions.PERSON)
                .keys
                .sorted()
                .first()
        val grant = RoleGrant(role, GrantScope.ENTITY, "E", clock.instant().minusSeconds(60))
        r.accept(
            AuthorityChanged(
                tenant,
                "u1",
                1,
                AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant),
            ),
        )
        r.accept(
            AuthorityChanged(
                tenant,
                "agent1",
                2,
                AuthorityRecord.RoleGrantRecord(
                    "g2",
                    RoleGrantState.ACTIVE,
                    RoleGrant(agentGrantRole, GrantScope.CONSENT, null, clock.instant().minusSeconds(60)),
                ),
            ),
        )
        return r
    }

    protected val option =
        AtomicReference<FilerOption?>(FilerOption.DERIVED)

    protected val confirm =
        ConfirmFiling(
            replica,
            dueDates,
            GateModesAt { _, _ -> GateMode.BLOCKING },
            latest,
            ReleasedOverrideChecks { _, _ -> emptySet() },
            store,
            FilerOptionsAt { _, _, _ -> option.get() ?: error("the filer option is unreadable") },
        )

    protected fun person(id: String) = EndUserPrincipal(id, tenant, setOf(EntityRef("E")), true)

    protected fun machine(coverage: String) =
        MachinePrincipalFixtures.machine(
            "m-$coverage",
            tenant,
            MachineEntityCoverage.Entity(coverage),
            "filing-workflow",
        )

    protected fun controller(
        user: EndUserPrincipal? = null,
        machine: Principal? = null,
        confirmUseCase: ConfirmFiling = confirm,
    ): ReadinessController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                when (args[0]) {
                    EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE -> user
                    MachineTokenFilter.PRINCIPAL_ATTRIBUTE -> machine
                    else -> null
                }
            } as HttpServletRequest
        val fixture = DrainFixture()
        return ReadinessController(
            GetLatestReadinessCheck(
                object : ReadinessCheckReader {
                    override fun latest(
                        entityRef: String,
                        periodRef: String,
                    ): StoredReadinessCheck? = null

                    override fun page(
                        entityRef: String,
                        periodRef: String,
                        limit: Int,
                        before: Long?,
                    ): List<StoredReadinessCheck> = emptyList()
                },
                OverrideAuthority { _, _, _ -> false },
            ),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
            fixture.preview(),
            fixture.drain(),
            confirmUseCase,
            runner,
            zone,
            unusedFilingGateOverrides(),
        )
    }

    protected fun body(
        filedAt: String,
        ack: String = "A1",
        correction: Boolean = false,
    ) = ConfirmFilingRequest(java.time.OffsetDateTime.parse(filedAt), ack, null, correction)

    /** Status and body; a refusal is rendered by the advice of the controller, as the framework does. */
    protected fun post(
        c: ReadinessController,
        period: String,
        request: ConfirmFilingRequest,
    ): Pair<Int, String> {
        val thrown = runCatching { CurrentDeadline.callWith(deadline) { c.confirmFiling("E", period, request) } }
        val failure = thrown.exceptionOrNull()
        if (failure != null) {
            val response: ResponseEntity<String> =
                when (failure) {
                    is FilingAccessRefusal -> FilingAccessRefusalAdvice().refuse(failure)
                    is ComplianceRefusal -> ReadinessRefusalAdvice().refuse(failure)
                    is FilingPeriodRefusal -> FilingRefusalAdvice().refuse(failure)
                    is AmbiguousDueDateException -> FilingRefusalAdvice().ambiguous(failure)
                    else -> throw failure
                }
            return response.statusCode.value() to response.body!!
        }
        val response = thrown.getOrThrow()
        return response.statusCode.value() to mapper.writeValueAsString(response.body)
    }

    /** The pinned error-envelope schema (unpacked by `unpackClientContracts`); unset or missing fails the case. */
    protected fun envelopeErrors(text: String): List<String> {
        val dir = checkNotNull(System.getProperty("gst.client-contracts.dir")) { "gst.client-contracts.dir is not set" }
        val file = File(dir, "service-runtime/schemas/error-envelope.schema.json")
        check(file.isFile) { "the pinned error-envelope schema is missing: $file" }
        val schemas =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        return schemas.getSchema(file.readText()).validate(text, InputFormat.JSON).map { it.toString() }
    }
}
