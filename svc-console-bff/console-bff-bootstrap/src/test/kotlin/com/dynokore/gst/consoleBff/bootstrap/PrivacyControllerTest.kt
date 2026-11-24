package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.privacy.CorrectPersonalData
import com.dynokore.gst.consoleBff.application.privacy.CorrectedVersion
import com.dynokore.gst.consoleBff.application.privacy.CorrectionRequest
import com.dynokore.gst.consoleBff.application.privacy.CounterpartyCorrections
import com.dynokore.gst.consoleBff.application.privacy.EvidenceAnnotations
import com.dynokore.gst.consoleBff.application.privacy.PrivacyAuthority
import com.dynokore.gst.consoleBff.application.privacy.PrivacyCases
import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** INV-AC-27141: PrivacyController.correctPersonalData over the real use case with stubbed owners that count calls. */
class PrivacyControllerTest {
    private val json = JsonMapper.builder().build()
    private val caseId = UUID(7, 7).toString()
    private val at = Instant.parse("2026-11-20T01:02:03Z")
    private val ownerRefusal =
        OwnerRefusal(409, "MDM-014", "Owner title", "BLOCK", "MANUAL", "MASTERDATA_OWNER", "owner detail text")

    private class Stubs(
        var allow: Boolean = true,
        var status: OwnerReply<String?> = OwnerReply.Ok("OPEN"),
        var correction: OwnerReply<CorrectedVersion> =
            OwnerReply.Ok(
                CorrectedVersion(Instant.parse("2026-11-20T01:02:03Z")),
            ),
        var annotation: OwnerReply<Unit> = OwnerReply.Ok(Unit),
    ) {
        var authorityCalls = 0
        var caseReads = 0
        var corrections = 0
        var annotations = 0

        fun ownerCalls() = caseReads + corrections + annotations
    }

    private fun mvc(stubs: Stubs): MockMvc {
        val authority =
            PrivacyAuthority { _, _, _ ->
                stubs.authorityCalls++
                stubs.allow
            }
        val cases =
            PrivacyCases {
                stubs.caseReads++
                stubs.status
            }
        val corrections =
            CounterpartyCorrections { _: String, _: String, _: CorrectionRequest ->
                stubs.corrections++
                stubs.correction
            }
        val annotations =
            EvidenceAnnotations { _, _, _, _, _ ->
                stubs.annotations++
                stubs.annotation
            }
        return MockMvcBuilders
            .standaloneSetup(
                PrivacyController(
                    CorrectPersonalData(authority, cases, corrections, annotations),
                    PrivacyProblemMapper(),
                ),
            ).build()
    }

    private fun bodyOf(vararg without: String): String {
        val members =
            linkedMapOf(
                "entityRef" to "E1",
                "counterpartyRef" to "C1",
                "ackId" to "ACK-1",
                "field" to "legalName",
                "correctedValue" to "New Name Pte Ltd",
                "reason" to "data subject request",
            )
        without.forEach { members.remove(it) }
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }

    private fun call(
        mvc: MockMvc,
        content: String = bodyOf(),
        authenticated: Boolean = true,
    ): MvcResult {
        val request =
            post("/bff/privacy-cases/$caseId/corrections")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .content(content)
        if (authenticated) {
            request.requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "U1")
            request.requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1")
        }
        return mvc.perform(request).andReturn()
    }

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun problem(
        result: MvcResult,
        status: Int,
        code: String,
    ) {
        result.response.status shouldBe status
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe code
    }

    @Test
    fun `INV-AC-27141 a valid call gives 200 with the contract example shape and one correction and one annotation`() {
        val stubs = Stubs()
        val result = call(mvc(stubs))
        result.response.status shouldBe 200
        val b = body(result)
        b.propertyNames().asSequence().toSet() shouldBe setOf("caseId", "field", "correctedValue", "correctedAt")
        b["caseId"].asString() shouldBe caseId
        b["field"].asString() shouldBe "legalName"
        b["correctedValue"].asString() shouldBe "New Name Pte Ltd"
        Instant.parse(b["correctedAt"].asString()) shouldBe at
        stubs.corrections shouldBe 1
        stubs.annotations shouldBe 1
    }

    @Test
    fun `INV-AC-27141 a request without a session answers 401 and calls nothing`() {
        val stubs = Stubs()
        val result = call(mvc(stubs), authenticated = false)
        result.response.status shouldBe 401
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        stubs.authorityCalls shouldBe 0
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a missing entityRef gives 400 SRT-001 and no owner call`() {
        val stubs = Stubs()
        problem(call(mvc(stubs), bodyOf("entityRef")), 400, "SRT-001")
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a missing counterpartyRef gives 400 SRT-001 and no owner call`() {
        val stubs = Stubs()
        problem(call(mvc(stubs), bodyOf("counterpartyRef")), 400, "SRT-001")
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a missing ackId gives 400 SRT-001 and no owner call`() {
        val stubs = Stubs()
        problem(call(mvc(stubs), bodyOf("ackId")), 400, "SRT-001")
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a null identifier gives 400 SRT-001 and no owner call`() {
        val stubs = Stubs()
        problem(call(mvc(stubs), bodyOf("ackId").dropLast(1) + ",\"ackId\":null}"), 400, "SRT-001")
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 an unsupported field counterpartyName gives 400 SRT-001 and no owner call`() {
        val stubs = Stubs()
        problem(call(mvc(stubs), bodyOf().replace("\"legalName\"", "\"counterpartyName\"")), 400, "SRT-001")
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a caller without the permission gets 403 SRT-004 and no owner call`() {
        val stubs = Stubs(allow = false)
        problem(call(mvc(stubs)), 403, "SRT-004")
        stubs.authorityCalls shouldBe 1
        stubs.ownerCalls() shouldBe 0
    }

    @Test
    fun `INV-AC-27141 a CLOSED case gives 404 SRT-005 and no write`() {
        val stubs = Stubs(status = OwnerReply.Ok("CLOSED"))
        problem(call(mvc(stubs)), 404, "SRT-005")
        stubs.corrections shouldBe 0
        stubs.annotations shouldBe 0
    }

    @Test
    fun `INV-AC-27141 an unknown case gives 404 SRT-005 and no write`() {
        val stubs = Stubs(status = OwnerReply.Ok(null))
        problem(call(mvc(stubs)), 404, "SRT-005")
        stubs.corrections shouldBe 0
        stubs.annotations shouldBe 0
    }

    @Test
    fun `INV-AC-27141 an owner refusal on the correction is relayed with its status and code`() {
        val stubs = Stubs(correction = OwnerReply.Refused(ownerRefusal))
        val result = call(mvc(stubs))
        problem(result, 409, "MDM-014")
        stubs.annotations shouldBe 0
    }

    @Test
    fun `INV-AC-27141 an owner refusal on the case read and on the annotation is relayed with its code`() {
        problem(call(mvc(Stubs(status = OwnerReply.Refused(ownerRefusal)))), 409, "MDM-014")
        problem(call(mvc(Stubs(annotation = OwnerReply.Refused(ownerRefusal)))), 409, "MDM-014")
    }

    @Test
    fun `INV-AC-27141 an unavailable owner gives 503 SRT-013 without the owner text`() {
        for (stubs in listOf(
            Stubs(status = OwnerReply.Unavailable("owner secret text")),
            Stubs(correction = OwnerReply.Unavailable("owner secret text")),
            Stubs(annotation = OwnerReply.Unavailable("owner secret text")),
        )) {
            val result = call(mvc(stubs))
            problem(result, 503, "SRT-013")
            result.response.contentAsString shouldNotContain "owner secret text"
        }
    }
}
