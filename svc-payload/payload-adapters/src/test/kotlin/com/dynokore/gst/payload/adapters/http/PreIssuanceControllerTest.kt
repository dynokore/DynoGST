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
import org.springframework.http.ResponseEntity
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
        type: DraftDocument.SubmissionType? = DraftDocument.SubmissionType.TYPE_1A,
        gstn: String? = "M90054321K",
        invoiceDate: LocalDate? = date,
        amount: String? = "9.00",
    ) = DraftDocument(
        "E-1",
        "SGD",
        BigDecimal("109.00"),
        type,
        gstn,
        invoiceDate,
        amount?.let(::BigDecimal),
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
    fun `INV-AC-26308 each left-out field is 400 SRT-001 and the register is never asked`() {
        listOf(
            draft(type = null),
            draft(gstn = null),
            draft(invoiceDate = null),
            draft(amount = null),
        ).forEach { incomplete ->
            val response = call(notRegistered, incomplete)
            response.statusCode.value() shouldBe 400
            response.headers.contentType
                ?.toString()
                .orEmpty() shouldBe "application/problem+json"
            json.readTree(textOf(response))["code"].asString() shouldBe "SRT-001"
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
}
