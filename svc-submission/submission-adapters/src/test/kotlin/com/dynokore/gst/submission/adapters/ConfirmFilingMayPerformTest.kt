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

/** `confirmFiling` refusal of an agent without FILE_GST_RETURN (INV-AC-928). */
class ConfirmFilingMayPerformTest : ConfirmFilingControllerFixture() {
    /** INV-AC-928: the agent's consent lists everything but FILE_GST_RETURN; the refusal names a party kind. */
    private fun refusedAgent(): Pair<Int, String> {
        consent(ConsentScopeItem.SUBMIT_INVOICE_DATA)
        return post(controller(person("agent1")), "P", body("2026-04-28T20:00:00Z"))
    }

    private fun mayPerform(json: tools.jackson.databind.JsonNode): List<String> =
        json["mayPerform"].let { n -> (0 until n.size()).map { n[it].asString() } }

    private fun noFactNoSnapshot() {
        store.stored.size shouldBe 0
    }

    @Test
    fun `INV-AC-928 an agent without FILE_GST_RETURN and option DERIVED is refused 403 SRT-004 naming CLIENT`() {
        option.set(FilerOption.DERIVED)
        val (status, text) = refusedAgent()
        status shouldBe 403
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "SRT-004"
        mayPerform(json) shouldBe listOf("CLIENT")
        json["mayPerform"].size() shouldBe 1
        envelopeErrors(text) shouldBe emptyList()
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 option ALWAYS_CLIENT answers mayPerform CLIENT`() {
        option.set(FilerOption.ALWAYS_CLIENT)
        val (status, text) = refusedAgent()
        status shouldBe 403
        mayPerform(mapper.readTree(text)) shouldBe listOf("CLIENT")
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 option EITHER_PARTY answers mayPerform CLIENT`() {
        option.set(FilerOption.EITHER_PARTY)
        val (status, text) = refusedAgent()
        status shouldBe 403
        mayPerform(mapper.readTree(text)) shouldBe listOf("CLIENT")
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 an agent whose consent lists FILE_GST_RETURN is not refused and the 200 body has no mayPerform`() {
        consent(ConsentScopeItem.FILE_GST_RETURN)
        val (status, text) = post(controller(person("agent1")), "P", body("2026-04-28T20:00:00Z"))
        status shouldBe 200
        mapper.readTree(text).has("mayPerform") shouldBe false
        text shouldNotContain "mayPerform"
        store.stored.size shouldBe 1
    }

    @Test
    fun `INV-AC-928 a consent listing every item except FILE_GST_RETURN is refused 403 SRT-004 and stores nothing`() {
        consent(*ConsentScopeItem.entries.filter { it != ConsentScopeItem.FILE_GST_RETURN }.toTypedArray())
        val (status, text) = post(controller(person("agent1")), "P", body("2026-04-28T20:00:00Z"))
        status shouldBe 403
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "SRT-004"
        mayPerform(json) shouldBe listOf("CLIENT")
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 an AUTHORITY_UNAVAILABLE refusal answers 403 SRT-004 with no mayPerform and stores nothing`() {
        val cold =
            ConfirmFiling(
                AuthorityReplica(clock, roleMap),
                dueDates,
                GateModesAt { _, _ -> GateMode.BLOCKING },
                latest,
                ReleasedOverrideChecks { _, _ -> emptySet() },
                store,
                FilerOptionsAt { _, _, _ -> option.get() ?: error("the filer option is unreadable") },
            )
        val (status, text) =
            post(
                controller(person("agent1"), confirmUseCase = cold),
                "P",
                body("2026-04-28T20:00:00Z"),
            )
        status shouldBe 403
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "SRT-004"
        json.has("mayPerform") shouldBe false
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 an unreadable filer option still answers 403 SRT-004 with no mayPerform member`() {
        option.set(null)
        val (status, text) = refusedAgent()
        status shouldBe 403
        val json = mapper.readTree(text)
        json["code"].asString() shouldBe "SRT-004"
        json.has("mayPerform") shouldBe false
        noFactNoSnapshot()
    }

    @Test
    fun `INV-AC-928 a person with no grant and a refused getReadiness carry no mayPerform`() {
        val (status, text) = post(controller(person("u2")), "P", body("2026-04-28T20:00:00Z"))
        status shouldBe 403
        text shouldNotContain "mayPerform"
        val other = ReadinessRefusalAdvice().refuse(ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED))
        other.statusCode.value() shouldBe 403
        other.body!! shouldNotContain "mayPerform"
    }
}
