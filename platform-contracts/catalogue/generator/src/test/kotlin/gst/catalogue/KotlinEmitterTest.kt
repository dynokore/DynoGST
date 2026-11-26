package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KotlinEmitterTest {
    private val rows =
        listOf(
            ParsedErrorCode(
                code = "ACC-001",
                area = "ACC",
                number = 1,
                conditionRaw = "raw",
                conditionCore = """A "quoted" condition with a \ backslash""",
                revisionNote = null,
                severity = Severity.WARN,
                severityRaw = "WARN",
                disposition = Disposition.MANUAL,
                dispositionRaw = "MANUAL",
                owner = "tenant.admin",
            ),
            ParsedErrorCode(
                code = "ACC-003",
                area = "ACC",
                number = 3,
                conditionRaw = "raw",
                conditionCore = "External grant expired",
                revisionNote = null,
                severity = Severity.INFO,
                severityRaw = "INFO",
                disposition = null,
                dispositionRaw = "None",
                owner = "tenant.admin",
            ),
        )

    @Test
    fun `emits one enum constant per row named AREA_NNN`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("ACC_001(\"ACC-001\""))
        assertTrue(source.contains("ACC_003(\"ACC-003\""))
    }

    @Test
    fun `escapes quotes and backslashes in the condition literal`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("""A \"quoted\" condition with a \\ backslash"""))
    }

    @Test
    fun `a null disposition is emitted as the literal null`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("Severity.INFO, null, \"tenant.admin\""))
    }

    @Test
    fun `records the pinned source commit in the generated header`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("deadbeef"))
        assertTrue(source.lines().first().startsWith("// GENERATED"))
    }

    private val withMembers =
        rows[0].copy(
            disposition = Disposition.OVERRIDABLE,
            willTransmit = """Sent "as entered" with a \ slash""",
            residualRisk = "Possible rejection.",
        )

    @Test
    fun `the emitted entry line of a row with members ends with both escaped literals`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", listOf(withMembers), "deadbeef")
        assertTrue(
            source.contains("\"tenant.admin\", \"Sent \\\"as entered\\\" with a \\\\ slash\", \"Possible rejection.\"),"),
            source,
        )
    }

    @Test
    fun `the emitted entry line of a row without members ends with null, null`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertEquals(2, source.lines().count { it.trim().startsWith("ACC_00") && it.endsWith("\"tenant.admin\", null, null),") })
    }

    @Test
    fun `the emitted class header lists the two members after owner`() {
        val source = KotlinEmitter.emit("com.dynokore.gst.catalogue", rows, "deadbeef")
        assertTrue(source.contains("val owner: String, val willTransmit: String?, val residualRisk: String?) {"))
    }

    private val vendored = File("../errors/error-codes.yaml")
    private val six = listOf("MDM-003", "RUL-007", "PAY-006", "PAY-011", "SUB-015", "PER-001")

    private fun rowLines(json: String) = json.lines().filter { it.trim().startsWith("{ \"code\"") }.associateBy { Regex("\"code\": \"([^\"]+)\"").find(it)!!.groupValues[1] }

    @Test
    fun `the JSON of a row without members equals the pre-change text byte for byte`() {
        val json = CatalogueArtefact.render(listOf(rows[0]), "1.0.0", "deadbeef")
        val expected =
            "    { \"code\": \"ACC-001\", \"condition\": \"A \\\"quoted\\\" condition with a \\\\ backslash\", " +
                "\"severity\": \"WARN\", \"disposition\": \"MANUAL\", \"owner\": \"tenant.admin\", \"revisionNote\": null }"
        assertTrue(json.lines().contains(expected), json)
    }

    @Test
    fun `the JSON of a row with members holds both`() {
        val json = CatalogueArtefact.render(listOf(withMembers), "1.0.0", "deadbeef")
        assertTrue(json.contains("\"willTransmit\": \"Sent \\\"as entered\\\" with a \\\\ slash\", \"residualRisk\": \"Possible rejection.\" }"), json)
    }

    @Test
    fun `the vendored artefact differs from the member-free artefact only by the members of the six rows`() {
        val parsed = ErrorCodeYamlParser.parse(vendored)
        val withNew = rowLines(CatalogueArtefact.render(parsed, "1.0.0", "c"))
        val before = rowLines(CatalogueArtefact.render(parsed.map { it.copy(willTransmit = null, residualRisk = null) }, "1.0.0", "c"))
        assertEquals(before.keys, withNew.keys)
        val withMembersCodes = parsed.filter { it.willTransmit != null }.map { it.code }
        assertEquals(six.sorted(), withMembersCodes.sorted())
        for ((code, line) in withNew) {
            if (code in six) {
                val row = parsed.single { it.code == code }
                val tail = ", \"willTransmit\": ${jsonText(row.willTransmit!!)}, \"residualRisk\": ${jsonText(row.residualRisk!!)} }"
                assertEquals(before.getValue(code).removeSuffix(",").removeSuffix(" }") + tail, line.removeSuffix(","), code)
            } else {
                assertEquals(before.getValue(code), line, code)
            }
        }
    }

    private fun jsonText(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
