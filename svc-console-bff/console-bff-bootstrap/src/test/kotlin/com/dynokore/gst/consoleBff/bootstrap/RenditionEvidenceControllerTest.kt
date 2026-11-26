package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.authz.ConsoleReadAuthorisationFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRead
import com.dynokore.gst.consoleBff.application.renditionevidence.AttestationRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.EvidencePage
import com.dynokore.gst.consoleBff.application.renditionevidence.GetEntityRenditionEvidence
import com.dynokore.gst.consoleBff.application.renditionevidence.PageReply
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckArtefactRecord
import com.dynokore.gst.consoleBff.application.renditionevidence.SampleCheckRead
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * INV-AC-27607: GET /bff/entities/{entityRef}/rendition-evidence with the real read filter and the production
 * permission table in the chain, a scripted replica and fake owner clients. The granted role has a name that exists
 * nowhere in the product, so only a decision by permission key lets the holder through.
 */
class RenditionEvidenceControllerTest {
    private class FixedClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.parse("2026-11-26T10:00:00Z")
    }

    private data class Read(
        val entityRef: String,
        val limit: Int,
        val cursor: String?,
    )

    private val attestation =
        AttestationRecord("TAX_INVOICE", "AGENT", "U7", Instant.parse("2026-11-01T02:03:04Z"), 3)
    private val sampleCheck =
        SampleCheckArtefactRecord("SAMPLE_CHECK", "sample-1", "a".repeat(64), Instant.parse("2026-11-02T05:06:07Z"))

    private class FakeAttestations(
        var reply: PageReply<AttestationRecord>,
    ) : AttestationRead {
        val reads = mutableListOf<Read>()

        override fun page(
            entityRef: String,
            limit: Int,
            cursor: String?,
        ): PageReply<AttestationRecord> {
            reads += Read(entityRef, limit, cursor)
            return reply
        }
    }

    private class FakeSampleChecks(
        var reply: PageReply<SampleCheckArtefactRecord>,
    ) : SampleCheckRead {
        val reads = mutableListOf<Read>()

        override fun page(
            entityRef: String,
            limit: Int,
            cursor: String?,
        ): PageReply<SampleCheckArtefactRecord> {
            reads += Read(entityRef, limit, cursor)
            return reply
        }
    }

    private val attestations = FakeAttestations(PageReply.Ok(EvidencePage(listOf(attestation), "ra-next")))
    private val sampleChecks = FakeSampleChecks(PageReply.Ok(EvidencePage(listOf(sampleCheck), "sc-next")))
    private val json = JsonMapper.builder().build()
    private val clock = FixedClock()
    private val tenant = TenantId.of("11111111-1111-1111-1111-111111111111")
    private val askedKeys = mutableListOf<String>()
    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { key ->
                askedKeys += key
                if (key == KEY) mapOf(FIXTURE_ROLE to CellAllowance.PERMITTED) else emptyMap()
            },
        )

    private fun grant(entity: String = "E1") {
        replica.onSnapshotComplete(1)
        replica.accept(
            AuthorityChanged(
                tenant,
                "U1",
                2,
                AuthorityRecord.RoleGrantRecord(
                    "g-$entity",
                    RoleGrantState.ACTIVE,
                    RoleGrant(FIXTURE_ROLE, GrantScope.ENTITY, entity, clock.instant().minusSeconds(60)),
                ),
            ),
        )
    }

    private fun controller() =
        RenditionEvidenceController(
            GetEntityRenditionEvidence(attestations, sampleChecks),
            OverridesProblemMapper(),
        )

    private fun mvc(permissions: CatalogueReadPermissions = CatalogueReadPermissions()): MockMvc =
        MockMvcBuilders
            .standaloneSetup(controller())
            .addFilters<StandaloneMockMvcBuilder>(ConsoleReadAuthorisationFilter(permissions, replica, clock))
            .build()

    private fun call(
        path: String = PATH,
        mvc: MockMvc = mvc(),
        principal: String? = "U1",
        tenantId: String? = tenant.toString(),
    ): MvcResult {
        val request = get(path).accept(MediaType.ALL)
        principal?.let { request.requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, it) }
        tenantId?.let { request.requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, it) }
        return mvc.perform(request).andReturn()
    }

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun assertProblem(
        result: MvcResult,
        status: Int,
        code: String,
    ) {
        result.response.status shouldBe status
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe code
    }

    private fun assertNoOwnerCall() {
        attestations.reads shouldBe emptyList()
        sampleChecks.reads shouldBe emptyList()
    }

    private fun names(node: JsonNode) = node.propertyNames().toSet()

    @Test
    fun `INV-AC-27607 a holder gets the composed pages of the contract`() {
        grant()
        val result = call("$PATH?limit=2&attestationsCursor=ra-1&sampleChecksCursor=sc-1")
        result.response.status shouldBe 200
        result.response.contentType shouldContain "application/json"
        val answer = body(result)
        names(answer) shouldBe setOf("attestations", "sampleChecks")
        names(answer["attestations"]) shouldBe setOf("items", "nextCursor")
        answer["attestations"]["nextCursor"].asString() shouldBe "ra-next"
        answer["attestations"]["items"].size() shouldBe 1
        val record = answer["attestations"]["items"][0]
        names(record) shouldBe setOf("documentClass", "renderer", "attestedBy", "attestedAt", "sequence")
        record["documentClass"].asString() shouldBe "TAX_INVOICE"
        record["renderer"].asString() shouldBe "AGENT"
        record["attestedBy"].asString() shouldBe "U7"
        Instant.parse(record["attestedAt"].asString()) shouldBe attestation.attestedAt
        record["sequence"].asLong() shouldBe 3
        names(answer["sampleChecks"]) shouldBe setOf("items", "nextCursor")
        answer["sampleChecks"]["nextCursor"].asString() shouldBe "sc-next"
        answer["sampleChecks"]["items"].size() shouldBe 1
        val artefact = answer["sampleChecks"]["items"][0]
        names(artefact) shouldBe setOf("artefactType", "reference", "hash", "recordedAt")
        artefact["artefactType"].asString() shouldBe "SAMPLE_CHECK"
        artefact["reference"].asString() shouldBe "sample-1"
        artefact["hash"].asString() shouldBe "a".repeat(64)
        Instant.parse(artefact["recordedAt"].asString()) shouldBe sampleCheck.recordedAt
        attestations.reads shouldBe listOf(Read("E1", 2, "ra-1"))
        sampleChecks.reads shouldBe listOf(Read("E1", 2, "sc-1"))
        askedKeys.toSet() shouldBe setOf(KEY)
    }

    @Test
    fun `INV-AC-27607 the last pages carry no nextCursor, the default limit is 100 and 1 and 500 are served`() {
        grant()
        attestations.reply = PageReply.Ok(EvidencePage(emptyList(), null))
        sampleChecks.reply = PageReply.Ok(EvidencePage(emptyList(), null))
        val answer = body(call())
        names(answer["attestations"]) shouldBe setOf("items")
        names(answer["sampleChecks"]) shouldBe setOf("items")
        call("$PATH?limit=1").response.status shouldBe 200
        call("$PATH?limit=500").response.status shouldBe 200
        attestations.reads.map { it.limit } shouldBe listOf(100, 1, 500)
        sampleChecks.reads.map { it.limit } shouldBe listOf(100, 1, 500)
    }

    @Test
    fun `INV-AC-27607 a denied decision gives 403 and no owner call`() {
        replica.onSnapshotComplete(1)
        assertProblem(call(), 403, ClosedErrorCode.ACCESS_DENIED.code)
        assertNoOwnerCall()
        // a grant on another entity, or on a look-alike of it, is no grant on this one
        grant("E10")
        grant("e1")
        assertProblem(call(), 403, ClosedErrorCode.ACCESS_DENIED.code)
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 without the filter entry of the route the call is refused and no owner is called`() {
        grant()
        val without =
            CatalogueReadPermissions(
                entries = CatalogueReadPermissions.ENTRIES.filterNot { it.operationId == OPERATION },
            )
        assertProblem(call(mvc = mvc(without)), 403, ClosedErrorCode.ACCESS_DENIED.code)
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 a request without principal or tenant gives 401 from the controller and no owner call`() {
        // without the filter, so that the answer is the controller's own
        val bare = MockMvcBuilders.standaloneSetup(controller()).build()
        for ((principal, tenantId) in listOf(null to tenant.toString(), "U1" to null, null to null)) {
            assertProblem(
                call(mvc = bare, principal = principal, tenantId = tenantId),
                401,
                ClosedErrorCode.UNAUTHENTICATED.code,
            )
        }
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 limit 0, a negative limit and limit 501 give 400 and no owner call`() {
        grant()
        for (limit in listOf(0, -1, 501)) {
            assertProblem(call("$PATH?limit=$limit"), 400, ClosedErrorCode.CONTRACT_VIOLATION.code)
        }
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 behind the validation proxy an entityRef over 40 characters or a high limit gives 400`() {
        val long = "E".repeat(41)
        grant(long)
        grant()
        // the generated interface is @Validated: the application serves the controller through this proxy
        val validation =
            MethodValidationPostProcessor().apply {
                isProxyTargetClass = true // as Spring Boot sets it (spring.aop.proxy-target-class)
                afterPropertiesSet()
            }
        val proxy = validation.postProcessAfterInitialization(controller(), "renditionEvidenceController")
        AopUtils.isCglibProxy(proxy) shouldBe true
        val proxied =
            MockMvcBuilders
                .standaloneSetup(proxy)
                .addFilters<StandaloneMockMvcBuilder>(
                    ConsoleReadAuthorisationFilter(CatalogueReadPermissions(), replica, clock),
                ).build()
        for (path in listOf("/bff/entities/$long/rendition-evidence", "$PATH?limit=501", "$PATH?limit=0")) {
            assertProblem(call(path, proxied), 400, ClosedErrorCode.CONTRACT_VIOLATION.code)
        }
        assertNoOwnerCall()
        call(mvc = proxied).response.status shouldBe 200
    }

    private fun refusal(status: Int) =
        OwnerRefusal(status, "OWN-$status", "owner title $status", null, null, null, "owner detail $status")

    private fun assertRelayed(status: Int) {
        val result = call()
        assertProblem(result, status, "OWN-$status")
        body(result)["title"].asString() shouldBe "owner title $status"
        body(result)["detail"].asString() shouldBe "owner detail $status"
    }

    @Test
    fun `INV-AC-27607 an owner Forbidden gives 403 with the owner envelope`() {
        grant()
        attestations.reply = PageReply.Forbidden(refusal(403))
        assertRelayed(403)
    }

    @Test
    fun `INV-AC-27607 an owner NotFound gives 404 with the owner envelope`() {
        grant()
        sampleChecks.reply = PageReply.NotFound(refusal(404))
        assertRelayed(404)
    }

    @Test
    fun `INV-AC-27607 an owner Invalid gives 400 with the owner envelope`() {
        grant()
        sampleChecks.reply = PageReply.Invalid(refusal(400))
        assertRelayed(400)
    }

    private fun assertOwnerUnavailable(result: MvcResult) {
        assertProblem(result, 503, ClosedErrorCode.SERVICE_NOT_READY.code)
        val text = result.response.contentAsString
        for (leak in listOf("secret-reason", "TAX_INVOICE", "sample-1", "attestations", "sampleChecks")) {
            text shouldNotContain leak
        }
    }

    @Test
    fun `INV-AC-27607 an unavailable tenancy owner gives the owner-unavailable problem`() {
        grant()
        attestations.reply = PageReply.Unavailable("secret-reason")
        assertOwnerUnavailable(call())
    }

    @Test
    fun `INV-AC-27607 an unavailable evidence owner gives the owner-unavailable problem`() {
        grant()
        sampleChecks.reply = PageReply.Unavailable("secret-reason")
        assertOwnerUnavailable(call())
    }

    @Test
    fun `INV-AC-27607 a renderer or artefact type outside the contract gives owner-unavailable and no partial page`() {
        grant()
        for (renderer in listOf("agent", "AGENT ", "OTHER")) {
            attestations.reply =
                PageReply.Ok(EvidencePage(listOf(attestation, attestation.copy(renderer = renderer)), null))
            assertOwnerUnavailable(call())
        }
        attestations.reply = PageReply.Ok(EvidencePage(listOf(attestation), null))
        for (type in listOf("sample_check", "SAMPLE_CHECK2")) {
            sampleChecks.reply =
                PageReply.Ok(EvidencePage(listOf(sampleCheck, sampleCheck.copy(artefactType = type)), null))
            assertOwnerUnavailable(call())
        }
    }

    @Test
    fun `INV-AC-27607 a path below or beside the route is not served`() {
        grant()
        for (path in listOf("$PATH/x", "$PATH/", "${PATH}s")) {
            // behind the filter there is no permission key for it; without the filter there is no handler for it
            call(path).response.status shouldBe 403
            call(path, mvc = MockMvcBuilders.standaloneSetup(controller()).build()).response.status shouldBe 404
        }
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 the other operation of the evidence tag still answers 501`() {
        val result =
            MockMvcBuilders
                .standaloneSetup(controller())
                .build()
                .perform(
                    post("/bff/entities/E1/audit-pack-exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"periodRef":"2026-Q3"}"""),
                ).andReturn()
        result.response.status shouldBe 501
        assertNoOwnerCall()
    }

    @Test
    fun `INV-AC-27607 the composed application has the use case and one controller for the route`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(GetEntityRenditionEvidence::class.java).size shouldBe 1
            context.getBeansOfType(RenditionEvidenceController::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-27607 the context does not start without the tenancy URL`(
        @TempDir dir: Path,
    ) {
        // application.yaml then holds only the unresolved placeholder
        val others = ConsoleContext.overrides(dir).filterNot { it.startsWith("$URL_PROPERTY=") }
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsoleBffApplication::class.java)
            .withPropertyValues(*others.toTypedArray())
            .run { context ->
                val failure = context.startupFailure
                failure shouldNotBe null
                generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                    "CONSOLE_TENANCY_SVC_URL"
            }
    }

    @Test
    fun `INV-AC-27607 application yaml gives each owner URL of the read exactly once and with no default`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/application.yaml")) { "application.yaml is missing" }
                .use { String(it.readBytes()) }
        val lines = text.lines().map { it.substringBefore(" #").trim() }
        for (name in listOf("tenancy", "evidence")) {
            lines.filter { it.startsWith("$name-svc-url:") } shouldBe
                listOf("$name-svc-url: \${CONSOLE_${name.uppercase()}_SVC_URL}")
        }
    }

    private companion object {
        const val KEY = "rendition-evidence.view"
        const val OPERATION = "getEntityRenditionEvidence"
        const val FIXTURE_ROLE = "fixture-holder"
        const val PATH = "/bff/entities/E1/rendition-evidence"
        const val URL_PROPERTY = "console.tenancy-svc-url"
    }
}
