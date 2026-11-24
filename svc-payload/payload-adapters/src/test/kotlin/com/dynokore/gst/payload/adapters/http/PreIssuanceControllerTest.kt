package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.application.preissuance.CheckPreIssuance
import com.dynokore.gst.payload.application.preissuance.RegisterLookup
import com.dynokore.gst.payload.application.preissuance.RegisterQuery
import com.dynokore.gst.payload.application.preissuance.RegisterStatusSource
import com.dynokore.gst.payload.contract.model.DraftDocument
import com.dynokore.gst.payload.contract.model.PreIssuanceVerdict
import com.dynokore.gst.payload.contract.model.ReasonItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import com.dynokore.platform.serviceruntime.http.BindingFailureAdvice
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

class PreIssuanceControllerTest {
    private val date = LocalDate.of(2026, 3, 20)
    private val queries = mutableListOf<RegisterQuery>()
    private val json = JsonMapper.builder().build()

    private fun controller(answer: RegisterLookup) =
        PreIssuanceController(
            CheckPreIssuance(
                RegisterStatusSource {
                    queries += it
                    answer
                },
            ),
        )

    private fun draft(
        type: DraftDocument.SubmissionType = DraftDocument.SubmissionType.TYPE_1A,
        gstn: String = "M90054321K",
        invoiceDate: LocalDate = date,
        amount: String = "9.00",
    ) = DraftDocument(
        "E-1",
        "SGD",
        BigDecimal("109.00"),
        type,
        gstn,
        invoiceDate,
        BigDecimal(amount),
    )

    private fun call(
        answer: RegisterLookup,
        draft: DraftDocument = draft(),
    ) = controller(answer).checkPreIssuance("flow", draft)

    private fun wrongful(severity: ReasonItem.Severity) =
        ReasonItem(
            "PAY-011",
            "Wrongful GST charge: the Supplier GSTN is not registered on the invoice date",
            "supplierGstn",
            severity,
            true,
        )

    /** A refusal carries the envelope as text, whatever the declared body type. */
    private fun textOf(response: ResponseEntity<*>): String = response.body as String

    private val notRegistered = RegisterLookup.Answered("NOT_REGISTERED", date)

    @Test
    fun `INV-AC-26308 BLOCK for TYPE_1A is 200 with the overridable PAY-011 reason of severity BLOCK`() {
        val response = call(notRegistered)
        response.statusCode.value() shouldBe 200
        response.body shouldBe
            PreIssuanceVerdict(PreIssuanceVerdict.Verdict.BLOCK, listOf(wrongful(ReasonItem.Severity.BLOCK)), null)
    }

    @Test
    fun `INV-AC-26308 WARN for TYPE_2 is 200 with the same reason of severity WARN`() {
        val response = call(notRegistered, draft(type = DraftDocument.SubmissionType.TYPE_2))
        response.statusCode.value() shouldBe 200
        response.body shouldBe
            PreIssuanceVerdict(PreIssuanceVerdict.Verdict.WARN, listOf(wrongful(ReasonItem.Severity.WARN)), null)
    }

    @Test
    fun `INV-AC-26308 PASS is 200 with no reasons and no flags`() {
        val response = call(RegisterLookup.Answered("REGISTERED", date))
        response.statusCode.value() shouldBe 200
        response.body shouldBe PreIssuanceVerdict(PreIssuanceVerdict.Verdict.PASS, emptyList(), null)
    }

    @Test
    fun `INV-AC-26308 an unavailable register is 200 WARN with no reason and the flag REGISTER_UNAVAILABLE`() {
        val response = call(RegisterLookup.Unavailable)
        response.statusCode.value() shouldBe 200
        response.body shouldBe
            PreIssuanceVerdict(
                PreIssuanceVerdict.Verdict.WARN,
                emptyList(),
                listOf(PreIssuanceVerdict.Flags.REGISTER_UNAVAILABLE),
            )
    }

    @Test
    fun `INV-AC-26308 each draft the check refuses is 400 SRT-001 and the register is never asked`() {
        listOf(
            draft(gstn = " "),
            draft(amount = "9.001"),
        ).forEach { refused ->
            val response = call(notRegistered, refused)
            response.statusCode.value() shouldBe 400
            response.headers.contentType
                ?.toString()
                .orEmpty() shouldBe "application/problem+json"
            json.readTree(textOf(response))["code"].asString() shouldBe "SRT-001"
        }
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26308 each left-out field is 400 SRT-001 and the register is never asked`() {
        // The contract types the four fields as required, so the body fails to deserialise before the controller runs;
        // BindingFailureAdvice (service-runtime, ARC-19) maps that failure to 400 SRT-001 once for every service.
        val mvc =
            MockMvcBuilders
                .standaloneSetup(controller(notRegistered))
                .setControllerAdvice(BindingFailureAdvice())
                .build()
        val complete =
            """{"entityRef":"E-1","currency":"SGD","totalIncludingTax":"109.00","submissionType":"TYPE_1A",""" +
                """"supplierGstn":"M90054321K","invoiceDate":"2026-03-20","gstAmount":"9.00"}"""
        fun post(body: String) =
            mvc.perform(
                MockMvcRequestBuilders
                    .post("/internal/v1/payload/pre-issuance-checks")
                    .header("X-Flow-Context", "flow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
        post(complete).andExpect(MockMvcResultMatchers.status().isOk)
        queries.clear()
        listOf("submissionType", "supplierGstn", "invoiceDate", "gstAmount").forEach { member ->
            val tree = json.readTree(complete) as ObjectNode
            tree.remove(member)
            val result = post(tree.toString()).andReturn().response
            result.status shouldBe 400
            result.contentType.orEmpty().startsWith("application/problem+json") shouldBe true
            json.readTree(result.contentAsString)["code"].asString() shouldBe "SRT-001"
        }
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26308 a draft the use case refuses is 400 SRT-001`() {
        val response: ResponseEntity<*> = call(notRegistered, draft(gstn = " "))
        response.statusCode.value() shouldBe 400
        json.readTree(textOf(response))["code"].asString() shouldBe "SRT-001"
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26695 a Decided BLOCK is 200 with one reason equal to the outcome and no flags member`() {
        val response = call(notRegistered)
        response.statusCode.value() shouldBe 200
        val body = checkNotNull(response.body as PreIssuanceVerdict)
        body.verdict shouldBe PreIssuanceVerdict.Verdict.BLOCK
        body.reasons.size shouldBe 1
        val reason = body.reasons.single()
        reason.code shouldBe "PAY-011"
        reason.field shouldBe "supplierGstn"
        reason.overridable shouldBe true
        reason.severity shouldBe ReasonItem.Severity.BLOCK
        body.flags shouldBe null
        json.writeValueAsString(body).contains("flags") shouldBe false
    }

    @Test
    fun `INV-AC-26695 a Decided WARN with the flag has flags REGISTER_UNAVAILABLE and reasons empty`() {
        val body = checkNotNull(call(RegisterLookup.Unavailable).body as PreIssuanceVerdict)
        body.verdict shouldBe PreIssuanceVerdict.Verdict.WARN
        body.flags shouldBe listOf(PreIssuanceVerdict.Flags.REGISTER_UNAVAILABLE)
        body.reasons shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26695 a Refused outcome is 400 SRT-001 with no verdict member`() {
        val response = call(notRegistered, draft(gstn = " "))
        response.statusCode.value() shouldBe 400
        val tree = json.readTree(textOf(response))
        tree["code"].asString() shouldBe "SRT-001"
        tree.has("verdict") shouldBe false
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26695 a Refused from the use case for gstAmount 9_001 is 400 SRT-001 with no verdict member`() {
        // 9.001 passes the controller's blank checks, so the real use case refuses it (more than 2 decimals).
        val response = call(notRegistered, draft(amount = "9.001"))
        response.statusCode.value() shouldBe 400
        val tree = json.readTree(textOf(response))
        tree["code"].asString() shouldBe "SRT-001"
        tree.has("verdict") shouldBe false
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26695 a blank supplierGstn, entityRef or currency is 400 SRT-001 and the use case is never called`() {
        listOf("", "   ").forEach { blank ->
            listOf(
                draft(gstn = blank),
                draft().copy(entityRef = blank),
                draft().copy(currency = blank),
            ).forEach { blankDraft ->
                val response = call(notRegistered, blankDraft)
                response.statusCode.value() shouldBe 400
                val tree = json.readTree(textOf(response))
                tree["code"].asString() shouldBe "SRT-001"
                tree.has("verdict") shouldBe false
            }
        }
        queries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26695 a supplierGstn with surrounding blanks but content reaches the use case unchanged`() {
        val response = call(notRegistered, draft(gstn = " 27AAA "))
        response.statusCode.value() shouldBe 200
        queries.size shouldBe 1
        queries.single().supplierGstn shouldBe " 27AAA "
    }
}
