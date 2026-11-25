package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.rendition.RENDITION_EVIDENCE_VIEW
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleCheck
import com.dynokore.gst.payload.application.rendition.ReadRenditionSampleMetadata
import com.dynokore.gst.payload.application.rendition.RenditionSampleReader
import com.dynokore.gst.payload.application.rendition.StoredSampleCheck
import com.dynokore.gst.payload.application.rendition.StoredSampleMetadata
import com.dynokore.gst.payload.contract.model.RecordRenditionSampleRequest
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

private const val ENTITY = "ENT-0001"
private const val CHECK_ID = "chk-0001"
private const val SHA = "e5c62df5dab5c87b6a015ef3d43597074d1eec433b15f51aec63b8582d0e4ab4"
private const val BY = "5b1f7c0e-2d4a-4c55-9a3e-0c1d2e3f4a5b"
private val SAMPLE = "%PDF-1.4\n".toByteArray(Charsets.UTF_8)
private val ARTEFACT =
    (
        "{\"checkId\":\"chk-0001\",\"checkedAt\":\"2026-03-20T03:20:00Z\",\"checkedBy\":\"$BY\"," +
            "\"entityRef\":\"ENT-0001\",\"missingWording\":[\"Buyer Created Tax Invoice\"],\"result\":\"FAILED\"," +
            "\"sampleSha256\":\"$SHA\",\"scenario\":\"SELF_BILLING\",\"submissionType\":\"TYPE_2\",\"textFound\":true}"
    ).toByteArray(Charsets.UTF_8)
private const val ARTEFACT_SHA = "e40fe0157e91bbb6f722d2d56f1b49ef2535a63a5aa9381f9ee0fb19d8dce76c"

private fun stored(
    entity: String = ENTITY,
    checkId: String = CHECK_ID,
    result: SampleResult = SampleResult.FAILED,
    artefact: ByteArray = ARTEFACT,
) = StoredSampleCheck(
    checkId,
    entity,
    "SELF_BILLING",
    SubmissionType.TYPE_2,
    SAMPLE,
    SHA,
    result,
    listOf("Buyer Created Tax Invoice"),
    true,
    Instant.parse("2026-03-20T03:20:00Z"),
    BY,
    artefact,
)

private class Rows(
    val checks: List<StoredSampleCheck>,
    val latest: Map<Pair<String, SubmissionType>, SampleResult>,
) : RenditionSampleReader {
    var findCalls = 0
    var metadataCalls = 0

    override fun find(
        entityRef: String,
        checkId: String,
    ): StoredSampleCheck? {
        findCalls++
        return checks.firstOrNull { it.entityRef == entityRef && it.checkId == checkId }
    }

    override fun findMetadata(
        entityRef: String,
        checkId: String,
    ): StoredSampleMetadata? {
        metadataCalls++
        return checks.firstOrNull { it.entityRef == entityRef && it.checkId == checkId }?.let {
            StoredSampleMetadata(
                it.checkId,
                it.entityRef,
                it.scenario,
                it.submissionType,
                it.sampleSha256,
                it.result,
                it.missingWording,
                it.textFound,
                it.checkedAt,
                it.checkedBy,
            )
        }
    }

    override fun latest(entityRef: String) = latest
}

/** Decides by the scoped key only: a user holds it or not; the role of the user is never an input. */
private class Keys(
    private val holders: Set<String>,
) : OverrideAuthority {
    val asked = mutableListOf<Triple<String, String, String>>()

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        asked += Triple(userId, permission, entityRef)
        return permission == RENDITION_EVIDENCE_VIEW && userId in holders
    }
}

class RenditionControllerTest {
    private val json = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build()
    private var caller: String? = "U1"
    private val keys = Keys(setOf("U1"))
    private var rows =
        Rows(
            listOf(stored(), stored("ENT-0002", "chk-0002", SampleResult.PASSED)),
            mapOf(("SELF_BILLING" to SubmissionType.TYPE_2) to SampleResult.FAILED),
        )

    private fun sut(): RenditionController {
        val http =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                val key = args?.firstOrNull()
                if (method.name == "getAttribute" && key == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) {
                    caller?.let { EndUserPrincipal(it, null, emptySet()) }
                } else {
                    null
                }
            } as HttpServletRequest
        return RenditionController(
            ReadRenditionSampleCheck(keys, rows),
            ReadRenditionSampleMetadata(keys, rows),
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            StaticListableBeanFactory(mapOf("request" to http)).getBeanProvider(HttpServletRequest::class.java),
        )
    }

    private fun body(response: ResponseEntity<*>): JsonNode = json.readTree(json.writeValueAsString(response.body))

    private fun ResponseEntity<*>.problem(
        status: Int,
        code: String,
    ) {
        statusCode.value() shouldBe status
        headers.contentType?.toString().orEmpty() shouldBe "application/problem+json"
        json.readTree(this.body as String)["code"].asString() shouldBe code
    }

    private fun names(node: JsonNode): Set<String> =
        node.properties().flatMap { (k, v) -> listOf(k) + names(v) }.toSet() +
            node.flatMap { if (it.isObject || it.isArray) names(it).toList() else emptyList() }

    @Test
    fun `INV-AC-27770 a holder reads every member of the record and the open standing`() {
        val response = sut().getRenditionSampleCheck(ENTITY, CHECK_ID)
        response.statusCode.value() shouldBe 200
        val tree = body(response)
        val c = tree["check"]
        c["checkId"].asString() shouldBe CHECK_ID
        c["entityRef"].asString() shouldBe ENTITY
        c["scenario"].asString() shouldBe "SELF_BILLING"
        c["submissionType"].asString() shouldBe "TYPE_2"
        c["sampleContent"].asString() shouldBe Base64.getEncoder().encodeToString(SAMPLE)
        c["sampleSha256"].asString() shouldBe SHA
        c["result"].asString() shouldBe "FAILED"
        c["textFound"].asBoolean() shouldBe true
        c["missingWording"].let { m -> (0 until m.size()).map { m[it].asString() } } shouldBe
            listOf("Buyer Created Tax Invoice")
        c["checkedAt"].asString() shouldBe "2026-03-20T03:20:00Z"
        c["checkedBy"].asString() shouldBe BY
        c["findingCode"].asString() shouldBe "PAY-014"
        c["artefactBytes"].asString() shouldBe Base64.getEncoder().encodeToString(ARTEFACT)
        val served = Base64.getDecoder().decode(c["artefactBytes"].asString())
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(served)) shouldBe ARTEFACT_SHA
        tree["standing"]["open"].asBoolean() shouldBe true
        keys.asked shouldBe listOf(Triple("U1", RENDITION_EVIDENCE_VIEW, ENTITY))
        rows.metadataCalls shouldBe 0
    }

    @Test
    fun `INV-AC-27770 artefactBytes are the stored bytes even where the fields cannot reproduce them`() {
        // The stored bytes keep the nanoseconds of checkedAt that the stored field no longer has.
        val kept =
            String(ARTEFACT, Charsets.UTF_8)
                .replace("03:20:00Z", "03:20:00.123456789Z")
                .toByteArray(Charsets.UTF_8)
        rows = Rows(listOf(stored(artefact = kept)), emptyMap())
        val check = body(sut().getRenditionSampleCheck(ENTITY, CHECK_ID))["check"]
        check["artefactBytes"].asString() shouldBe Base64.getEncoder().encodeToString(kept)
    }

    @Test
    fun `INV-AC-27770 a PASSED check is 200 with open false and no findingCode`() {
        rows =
            Rows(
                listOf(stored(result = SampleResult.PASSED)),
                mapOf(("SELF_BILLING" to SubmissionType.TYPE_2) to SampleResult.PASSED),
            )
        val tree = body(sut().getRenditionSampleCheck(ENTITY, CHECK_ID))
        tree["standing"]["open"].asBoolean() shouldBe false
        tree["check"]["result"].asString() shouldBe "PASSED"
        tree["check"].has("findingCode") shouldBe false
    }

    @Test
    fun `INV-AC-27770 a non-holder is 403 SRT-004 and no read happens`() {
        caller = "U9"
        sut().getRenditionSampleCheck(ENTITY, CHECK_ID).problem(403, "SRT-004")
        rows.findCalls shouldBe 0
    }

    @Test
    fun `INV-AC-27770 an unknown checkId and a checkId of another entity are 404 SRT-005`() {
        sut().getRenditionSampleCheck(ENTITY, "nope").problem(404, "SRT-005")
        sut().getRenditionSampleCheck(ENTITY, "chk-0002").problem(404, "SRT-005")
    }

    @Test
    fun `INV-AC-27770 without a principal the read is 401 SRT-003 and no decision is asked`() {
        caller = null
        sut().getRenditionSampleCheck(ENTITY, CHECK_ID).problem(401, "SRT-003")
        keys.asked shouldBe emptyList()
        rows.findCalls shouldBe 0
    }

    @Test
    fun `INV-AC-27770 the two write operations still answer 501`() {
        val controller = sut()
        val request =
            RecordRenditionSampleRequest("SELF_BILLING", RecordRenditionSampleRequest.SubmissionType.TYPE_2, "")
        controller.recordRenditionSample(ENTITY, request).statusCode.value() shouldBe 501
        controller.runSampleCheck(ENTITY, request).statusCode.value() shouldBe 501
    }

    @Test
    fun `INV-AC-27770 metadata a holder reads every metadata member and no bytes member at any depth`() {
        val response = sut().getRenditionSampleCheckMetadata(ENTITY, CHECK_ID)
        response.statusCode.value() shouldBe 200
        val tree = body(response)
        val c = tree["check"]
        c["checkId"].asString() shouldBe CHECK_ID
        c["entityRef"].asString() shouldBe ENTITY
        c["scenario"].asString() shouldBe "SELF_BILLING"
        c["submissionType"].asString() shouldBe "TYPE_2"
        c["sampleSha256"].asString() shouldBe SHA
        c["result"].asString() shouldBe "FAILED"
        c["textFound"].asBoolean() shouldBe true
        c["missingWording"].let { m -> (0 until m.size()).map { m[it].asString() } } shouldBe
            listOf("Buyer Created Tax Invoice")
        c["checkedAt"].asString() shouldBe "2026-03-20T03:20:00Z"
        c["checkedBy"].asString() shouldBe BY
        c["findingCode"].asString() shouldBe "PAY-014"
        tree["standing"]["open"].asBoolean() shouldBe true
        val all = names(tree)
        (all.contains("sampleContent") || all.contains("artefactBytes")) shouldBe false
        all.contains("sampleSha256") shouldBe true
        rows.findCalls shouldBe 0
        rows.metadataCalls shouldBe 1
    }

    @Test
    fun `INV-AC-27770 metadata of a PASSED check is 200 with open false`() {
        rows =
            Rows(
                listOf(stored(result = SampleResult.PASSED)),
                mapOf(("SELF_BILLING" to SubmissionType.TYPE_2) to SampleResult.PASSED),
            )
        val tree = body(sut().getRenditionSampleCheckMetadata(ENTITY, CHECK_ID))
        tree["standing"]["open"].asBoolean() shouldBe false
        tree["check"].has("findingCode") shouldBe false
    }

    @Test
    fun `INV-AC-27770 metadata for a non-holder is 403 SRT-004 and no read happens`() {
        caller = "U9"
        sut().getRenditionSampleCheckMetadata(ENTITY, CHECK_ID).problem(403, "SRT-004")
        rows.metadataCalls shouldBe 0
        rows.findCalls shouldBe 0
    }

    @Test
    fun `INV-AC-27770 metadata for an unknown checkId and a checkId of another entity is 404 SRT-005`() {
        sut().getRenditionSampleCheckMetadata(ENTITY, "nope").problem(404, "SRT-005")
        sut().getRenditionSampleCheckMetadata(ENTITY, "chk-0002").problem(404, "SRT-005")
    }

    @Test
    fun `INV-AC-27770 metadata without a principal is 401 SRT-003 and no decision is asked`() {
        caller = null
        sut().getRenditionSampleCheckMetadata(ENTITY, CHECK_ID).problem(401, "SRT-003")
        keys.asked shouldBe emptyList()
        rows.metadataCalls shouldBe 0
        rows.findCalls shouldBe 0
    }
}
