package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.ExportNotReady
import com.dynokore.gst.evidence.adapters.http.ExportsController
import com.dynokore.gst.evidence.adapters.http.ExportsRefusal
import com.dynokore.gst.evidence.adapters.http.ExportsRefusalAdvice
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.export.AuditPackClassSources
import com.dynokore.gst.evidence.application.export.AuditPackJobs
import com.dynokore.gst.evidence.application.export.ExportAuditPack
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.contract.model.ExportManifest
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExportsControllerTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val finished = Instant.parse("2026-11-15T09:00:00Z")
    private val reads = mutableListOf<Pair<TenantId, ExportScope>>()

    private inner class Jobs(
        private val job: ExportJob?,
    ) : ExportJobs {
        override fun find(
            tenant: TenantId,
            offboardingRef: String,
        ): ExportJob? = error("getEvidenceExport reads the latest job only")

        override fun findLatest(
            tenant: TenantId,
            scope: ExportScope,
        ): ExportJob? {
            reads += tenant to scope
            return job?.takeIf { it.tenant == tenant }
        }

        override fun register(request: ExportRequest): ExportJob = error("a read never writes")

        override fun markReady(
            job: ExportJob,
            manifestRef: String,
            itemCount: Int,
            contents: List<String>,
        ): ExportJob = error("a read never writes")

        override fun markFailed(
            job: ExportJob,
            reason: String,
        ): ExportJob = error("a read never writes")
    }

    private fun job(state: ExportState) =
        ExportJob(
            tenant = t1,
            offboardingRef = "OFF-1",
            exportRef = "EXP-1",
            state = state,
            manifestRef = "s3://bucket/manifest".takeIf { state == ExportState.READY },
            itemCount = 42.takeIf { state == ExportState.READY },
            failureReason = "no source".takeIf { state == ExportState.FAILED },
            finishedAt = finished.takeIf { state != ExportState.REQUESTED },
        )

    private fun controller(
        job: ExportJob?,
        caller: EndUserPrincipal? = EndUserPrincipal("user-42", t1, emptySet()),
    ): ExportsController {
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        return ExportsController(
            Jobs(job),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
            unused(AuditPackJobs::class.java),
            ExportAuditPack(
                AuditPackClassSources(emptyMap()),
                unused(AuditPackJobs::class.java),
                unused(ExportPackageStore::class.java),
                unused(IntegrityStore::class.java),
                unused(ExportChainIntegrity::class.java),
                clock,
            ),
            AuthorityReplica(
                clock,
                OperationRoleMap { emptyMap() },
                fallback = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable },
            ),
            AuditedReads(unused(AuditedOperations::class.java)),
            IntegrityStatusQueryHandler(unused(IntegrityStore::class.java)),
            clock,
            unused(TransactionRunner::class.java),
        )
    }

    private val clock = Clock.fixed(finished, ZoneOffset.UTC)

    /** A port the getEvidenceExport tests never reach: any call fails the test (INV-T-25920 adapts the constructor only). */
    private fun <T> unused(type: Class<T>): T =
        type.cast(
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, _ ->
                throw AssertionError("unexpected call of ${type.simpleName}.${method.name}")
            },
        )

    private fun code(body: String) =
        Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private fun notReady(
        job: ExportJob?,
        tenantId: UUID = t1.value,
    ): ResponseEntity<String> {
        val thrown = runCatching { controller(job).getEvidenceExport(tenantId) }.exceptionOrNull()
        val refused = thrown as? ExportNotReady ?: throw AssertionError("expected ExportNotReady, got $thrown")
        return ExportsRefusalAdvice().notReady(refused)
    }

    @Test
    fun `INV-AC-22950 a READY job answers 200 with the manifest members equal to the job`() {
        val response = controller(job(ExportState.READY)).getEvidenceExport(t1.value)
        response.statusCode.value() shouldBe 200
        val manifest = response.body!!
        manifest.tenantId shouldBe t1.value
        manifest.kind shouldBe ExportManifest.Kind.OFFBOARDING
        manifest.generatedAt shouldBe finished.atOffset(ZoneOffset.UTC)
        manifest.manifestUri shouldBe "s3://bucket/manifest"
        manifest.itemCount shouldBe 42
        reads shouldBe listOf(t1 to ExportScope.TENANT)
    }

    @Test
    fun `INV-AC-22950 a FAILED job answers 409 EVD-002 and the detail names FAILED`() {
        val response = notReady(job(ExportState.FAILED))
        response.statusCode.value() shouldBe 409
        code(response.body!!) shouldBe "EVD-002"
        response.body!!.contains("\"detail\":\"The export is not ready: FAILED\"") shouldBe true
    }

    @Test
    fun `INV-AC-22950 no job answers 409 EVD-002 and the detail names none`() {
        val response = notReady(null)
        response.statusCode.value() shouldBe 409
        code(response.body!!) shouldBe "EVD-002"
        response.body!!.contains("\"detail\":\"The export is not ready: none\"") shouldBe true
    }

    @Test
    fun `INV-AC-22950 a REQUESTED job answers 409 EVD-002 and the detail names REQUESTED`() {
        val response = notReady(job(ExportState.REQUESTED))
        response.statusCode.value() shouldBe 409
        code(response.body!!) shouldBe "EVD-002"
        response.body!!.contains("\"detail\":\"The export is not ready: REQUESTED\"") shouldBe true
    }

    @Test
    fun `INV-AC-22950 tenantId T2 while T1 is bound answers 403 SRT-004 and the reader is not called`() {
        val thrown = runCatching { controller(job(ExportState.READY)).getEvidenceExport(t2.value) }.exceptionOrNull()
        val refused = thrown as? ExportsRefusal ?: throw AssertionError("expected ExportsRefusal, got $thrown")
        val response = ExportsRefusalAdvice().refuse(refused)
        response.statusCode.value() shouldBe 403
        code(response.body!!) shouldBe "SRT-004"
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22950 a request without a bound tenant answers 401 and the reader is not called`() {
        val thrown = runCatching { controller(job(ExportState.READY), caller = null).getEvidenceExport(t1.value) }
        val refused = thrown.exceptionOrNull() as? ExportsRefusal ?: throw AssertionError("expected ExportsRefusal")
        ExportsRefusalAdvice().refuse(refused).statusCode.value() shouldBe 401
        reads shouldBe emptyList()
    }
}
