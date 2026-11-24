package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.DecisionInputFact
import com.dynokore.gst.payload.application.build.RejectionDecision
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * INV-AC-28355 (INV-T-28356): the decision bounds of the payload code equal the maxLength values of the published
 * PayloadRejected schema staged from the pinned bundle. Fails closed: nothing here skips; the test source holds no
 * numeric limit.
 */
class DecisionBoundsContractTest {
    private val mapper = JsonMapper()

    private fun schema(): JsonNode {
        val path =
            System.getProperty("gst.payload-rejected.schema")
                ?: error("system property gst.payload-rejected.schema is not set")
        val file = File(path)
        check(file.isFile) { "the staged PayloadRejected schema is missing: $path" }
        return mapper.readTree(file)
    }

    /** Walks [path] below the schema root; a missing member fails with the member name. */
    private fun maxLength(
        root: JsonNode,
        vararg path: String,
    ): Int {
        var node = root
        for (step in path) {
            node = node.get(step) ?: error("the schema has no member '$step' on the path ${path.joinToString(".")}")
        }
        val max = node.get("maxLength") ?: error("no maxLength at ${path.joinToString(".")}")
        check(max.isInt) { "maxLength at ${path.joinToString(".")} is not an integer" }
        return max.intValue()
    }

    private val decision = arrayOf("properties", "decisions", "items", "properties")
    private val inputProps = decision + arrayOf("inputs", "items", "properties")

    private fun name(root: JsonNode) = maxLength(root, *inputProps, "name")

    private fun value(root: JsonNode) = maxLength(root, *inputProps, "value")

    private fun ruleId(root: JsonNode) = maxLength(root, *decision, "ruleId")

    private fun assertion(root: JsonNode) = maxLength(root, *decision, "assertionText")

    @Test
    fun `INV-AC-28355 the input name bound equals the published maxLength`() {
        name(schema()) shouldBe DecisionInputFact.NAME_MAX
    }

    @Test
    fun `INV-AC-28355 the input value bound equals the published maxLength`() {
        value(schema()) shouldBe DecisionInputFact.VALUE_MAX
    }

    @Test
    fun `INV-AC-28355 the rule id bound equals the published maxLength`() {
        ruleId(schema()) shouldBe RejectionDecision.RULE_ID_MAX
    }

    @Test
    fun `INV-AC-28355 the assertion text bound equals the published maxLength`() {
        assertion(schema()) shouldBe RejectionDecision.ASSERTION_MAX
    }

    @Test
    fun `INV-AC-28355 a schema without the decisions member fails`() {
        val copy = schema().deepCopy() as ObjectNode
        (copy.get("properties") as ObjectNode).remove("decisions")
        val failure = assertThrows<IllegalStateException> { name(copy) }
        failure.message!! shouldContain "decisions"
        assertThrows<IllegalStateException> { value(copy) }
        assertThrows<IllegalStateException> { ruleId(copy) }
        assertThrows<IllegalStateException> { assertion(copy) }
    }

    @Test
    fun `INV-AC-28355 a changed constant is detected`() {
        val root = schema()
        (name(root) == DecisionInputFact.NAME_MAX + 1) shouldBe false
        (value(root) == DecisionInputFact.VALUE_MAX + 1) shouldBe false
        (ruleId(root) == RejectionDecision.RULE_ID_MAX + 1) shouldBe false
        (assertion(root) == RejectionDecision.ASSERTION_MAX + 1) shouldBe false
    }
}
