package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.resolution.PackTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** The `breach-terms` contents decoder and its import check (INV-REQ-1385). The hours are test data, not the terms. */
class BreachTermsPackContentsTest {
    private fun json(
        a: String? = "72",
        n: String? = "24",
        r: String? = "\"Act s 26D\"",
    ) = listOfNotNull(
        a?.let { "\"assessmentWithinHours\":$it" },
        n?.let { "\"notificationWithinHours\":$it" },
        r?.let { "\"sourceReference\":$it" },
    ).joinToString(",", "{", "}")

    private fun refusal(body: String) = PackContentsChecks().refusal("breach-terms", body.toByteArray())

    @Test
    fun `INV-AC-24937 complete contents decode to the two terms and the reference`() {
        BreachTermsPackContents.decode(json().toByteArray()) shouldBe BreachTerms(72, 24, "Act s 26D")
        refusal(json()) shouldBe null
    }

    @Test
    fun `INV-AC-24937 each missing member is refused naming it`() {
        listOf(
            json(a = null) to "assessmentWithinHours",
            json(n = null) to "notificationWithinHours",
            json(r = null) to "sourceReference",
        ).forEach { (body, member) ->
            refusal(body) shouldBe "breach-terms contents: $.$member is required"
            shouldThrow<PackContentsFailure> { BreachTermsPackContents.decode(body.toByteArray()) }.field shouldBe
                "$.$member"
        }
    }

    @Test
    fun `INV-AC-24937 a term of 0 or less is refused naming the member`() {
        listOf("0", "-1").forEach { v ->
            refusal(json(a = v)) shouldBe "breach-terms contents: $.assessmentWithinHours must be 1 or more: $v"
            refusal(json(n = v)) shouldBe "breach-terms contents: $.notificationWithinHours must be 1 or more: $v"
        }
        BreachTermsPackContents.decode(json(a = "1", n = "1").toByteArray()) shouldBe BreachTerms(1, 1, "Act s 26D")
    }

    @Test
    fun `INV-AC-24937 a non-integer term is refused naming the member`() {
        refusal(json(a = "24.5")) shouldBe
            "breach-terms contents: $.assessmentWithinHours must be a whole number of hours"
        refusal(json(n = "\"24\"")) shouldBe
            "breach-terms contents: $.notificationWithinHours must be a whole number of hours"
    }

    @Test
    fun `INV-AC-24937 an empty sourceReference is refused`() {
        refusal(json(r = "\"\"")) shouldContain "$.sourceReference"
    }

    @Test
    fun `INV-AC-24937 an extra member is accepted`() {
        val body = json().dropLast(1) + ",\"extra\":1}"
        BreachTermsPackContents.decode(body.toByteArray()) shouldBe BreachTerms(72, 24, "Act s 26D")
        refusal(body) shouldBe null
    }

    @Test
    fun `INV-AC-24937 a look-alike pack type is not checked`() {
        listOf("breach-term", "breach_terms", "BREACH_TERMS", "Breach-Terms", "breach-terms ").forEach {
            PackContentsChecks().refusal(it, "{}".toByteArray()) shouldBe null
        }
    }

    @Test
    fun `INV-AC-29341 the breach terms pack type has the wire value breach-terms`() {
        PackTypes.BREACH_TERMS.value shouldBe "breach-terms"
    }

    @Test
    fun `INV-AC-29341 contents that omit a term are refused under breach-terms naming the member`() {
        listOf(
            json(a = null) to "assessmentWithinHours",
            json(n = null) to "notificationWithinHours",
        ).forEach { (body, member) ->
            val text = PackContentsChecks().refusal("breach-terms", body.toByteArray()).shouldNotBeNull()
            text shouldStartWith "breach-terms contents:"
            text shouldContain member
        }
    }

    @Test
    fun `INV-AC-29341 the former value and look-alike pack types are not checked`() {
        listOf("BREACH_TERMS", "breach_terms", "breach-term", "Breach-Terms", "breach-terms ").forEach { type ->
            listOf(json(a = null), json(n = null)).forEach { body ->
                withClue("pack type '$type'") { PackContentsChecks().refusal(type, body.toByteArray()) shouldBe null }
            }
        }
    }
}
