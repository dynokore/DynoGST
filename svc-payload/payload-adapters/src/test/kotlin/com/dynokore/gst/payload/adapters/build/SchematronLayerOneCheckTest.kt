package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.packs.ReplicaPack
import com.dynokore.gst.payload.application.build.DecisionInputFact
import com.dynokore.gst.payload.application.build.DecisionPackVersion
import com.dynokore.gst.payload.application.build.PackVersionFact
import com.dynokore.gst.payload.domain.FailedAssertion
import com.dynokore.gst.payload.domain.SchematronValidator
import com.dynokore.gst.payload.domain.Severity
import com.dynokore.gst.payload.domain.ValidationOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchematronLayerOneCheckTest {
    private val doc = """<Invoice><ID>INV-1</ID></Invoice>""".toByteArray()
    private val schematron = ReplicaPack("peppol-sg", "S-1", "{}")
    private val override =
        ReplicaPack("iras-map", "O-1", """{"escalations":[{"ruleId":"W-1","specificationVersions":["SG-1"]}]}""")

    private fun assertion(
        id: String?,
        role: String?,
        text: String = "text of $id",
        location: String = "/Invoice/ID",
    ) = FailedAssertion(id, location, "t", role, text)

    private fun check(
        failed: List<FailedAssertion>,
        packs: Map<String, ReplicaPack> = mapOf("schematron" to schematron, "severity-override" to override),
    ): SchematronLayerOneCheck {
        val validator =
            object : SchematronValidator {
                override fun validate(
                    packVersion: String,
                    document: ByteArray,
                ): ValidationOutcome {
                    packVersion shouldBe "S-1"
                    return ValidationOutcome(failed)
                }
            }
        return SchematronLayerOneCheck(validator) { packs[it] }
    }

    @Test
    fun `INV-AC-27232 a fatal assert is a PAY-002 block with one decision`() {
        val r = check(listOf(assertion("F-1", "fatal"))).check(doc, "SG-1")
        r.findings.single().severity shouldBe Severity.BLOCK
        r.findings.single().code shouldBe "PAY-002"
        val d = r.decisions.single()
        d.code shouldBe "PAY-002"
        d.ruleId shouldBe "F-1"
        d.inputs shouldBe listOf(DecisionInputFact("ID", "INV-1"))
    }

    @Test
    fun `INV-AC-27232 an escalated warning is a PAY-001 block`() {
        val r = check(listOf(assertion("W-1", "warning"))).check(doc, "SG-1")
        r.findings.single().code shouldBe "PAY-001"
        r.decisions.single().code shouldBe "PAY-001"
    }

    @Test
    fun `INV-AC-27232 a plain warning has no decision`() {
        val r = check(listOf(assertion("W-2", "warning"))).check(doc, "SG-1")
        r.findings.single().severity shouldBe Severity.WARN
        r.findings.single().code shouldBe null
        r.decisions shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27232 an unknown role is treated as fatal`() {
        check(
            listOf(assertion("X-1", "info"), assertion("X-2", null)),
        ).check(doc, "SG-1").findings.map { it.code } shouldBe
            listOf("PAY-002", "PAY-002")
    }

    @Test
    fun `INV-AC-27232 no active schematron pack raises`() {
        assertThrows<IllegalStateException> {
            check(emptyList(), mapOf("severity-override" to override)).check(doc, "SG-1")
        }
    }

    @Test
    fun `INV-AC-27232 a missing severity-override pack escalates nothing`() {
        val r = check(listOf(assertion("W-1", "warning")), mapOf("schematron" to schematron)).check(doc, "SG-1")
        r.findings.single().severity shouldBe Severity.WARN
        r.decisions shouldBe emptyList()
        r.packVersions shouldBe listOf(PackVersionFact("schematron", "S-1"))
    }

    @Test
    fun `INV-AC-27232 a name longer than the name bound is cut to the bound`() {
        val long = "n".repeat(DecisionInputFact.NAME_MAX + 5)
        val r = check(listOf(assertion("F-1", "fatal", location = "/Invoice/$long"))).check(doc, "SG-1")
        r.decisions
            .single()
            .inputs
            .single()
            .name shouldBe "n".repeat(DecisionInputFact.NAME_MAX)
    }

    @Test
    fun `INV-AC-27232 a value longer than the value bound is cut to the bound`() {
        val long = "v".repeat(DecisionInputFact.VALUE_MAX + 5)
        val bytes = "<Invoice><ID>$long</ID></Invoice>".toByteArray()
        val r = check(listOf(assertion("F-1", "fatal"))).check(bytes, "SG-1")
        r.decisions
            .single()
            .inputs
            .single()
            .value shouldBe "v".repeat(DecisionInputFact.VALUE_MAX)
    }

    @Test
    fun `INV-AC-27232 a name and a value within the bounds are unchanged`() {
        val name = "n".repeat(DecisionInputFact.NAME_MAX)
        val value = "v".repeat(DecisionInputFact.VALUE_MAX)
        val bytes = "<Invoice><$name>$value</$name></Invoice>".toByteArray()
        val r = check(listOf(assertion("F-1", "fatal", location = "/Invoice/$name"))).check(bytes, "SG-1")
        r.decisions
            .single()
            .inputs
            .single() shouldBe DecisionInputFact(name, value)
    }

    @Test
    fun `INV-AC-27232 the decision names the pack versions used`() {
        val r = check(listOf(assertion("F-1", "fatal"), assertion("W-1", "warning"))).check(doc, "SG-1")
        r.decisions[0].packVersions shouldBe listOf(DecisionPackVersion("peppol-sg", "S-1"))
        r.decisions[1].packVersions shouldBe
            listOf(DecisionPackVersion("peppol-sg", "S-1"), DecisionPackVersion("iras-map", "O-1"))
        r.packVersions shouldBe
            listOf(PackVersionFact("schematron", "S-1"), PackVersionFact("severity-override", "O-1"))
    }

    @Test
    fun `INV-AC-27232 rule id and assertion text are carried unchanged`() {
        val id = "R".repeat(DecisionInputFact.NAME_MAX - 30)
        val text = "t".repeat(DecisionInputFact.VALUE_MAX - 1)
        val d = check(listOf(assertion(id, "fatal", text))).check(doc, "SG-1").decisions.single()
        d.ruleId shouldBe id
        d.assertionText shouldBe text
        check(listOf(assertion(null, "fatal")))
            .check(doc, "SG-1")
            .decisions
            .single()
            .ruleId shouldBe "unidentified"
    }
}
