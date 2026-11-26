package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ErrorCodeYamlParserTest {
    private fun yamlFile(text: String) =
        Files.createTempFile("error-codes", ".yaml").toFile().apply { writeText(text) }

    @Test
    fun `parses a plain row`() {
        val file =
            yamlFile(
                """
                error_codes:
                - code: ING-001
                  condition: Unknown sourceSystem
                  severity: BLOCK
                  disposition: TERMINAL
                  owner: Integration
                """.trimIndent(),
            )
        val row = ErrorCodeYamlParser.parse(file).single()
        assertEquals("ING-001", row.code)
        assertEquals("ING", row.area)
        assertEquals(1, row.number)
        assertEquals("Unknown sourceSystem", row.conditionCore)
        assertNull(row.revisionNote)
        assertEquals(Severity.BLOCK, row.severity)
        assertEquals(Disposition.TERMINAL, row.disposition)
        assertEquals("Integration", row.owner)
    }

    @Test
    fun `an INFO code may carry no disposition`() {
        val file =
            yamlFile(
                """
                error_codes:
                - code: ING-005
                  condition: Out of scope for the registration period
                  severity: INFO
                  disposition: None — no queue item
                  owner: Onboarding
                """.trimIndent(),
            )
        val row = ErrorCodeYamlParser.parse(file).single()
        assertEquals(Severity.INFO, row.severity)
        assertNull(row.disposition)
    }

    @Test
    fun `severity and disposition reduce to their leading token`() {
        val file =
            yamlFile(
                """
                error_codes:
                - code: PAY-011
                  condition: Wrongful GST charge detected
                  severity: BLOCK on the Relay path; WARN elsewhere
                  disposition: OVERRIDABLE
                  owner: Tax operations
                """.trimIndent(),
            )
        val row = ErrorCodeYamlParser.parse(file).single()
        assertEquals(Severity.BLOCK, row.severity)
        assertEquals(Disposition.OVERRIDABLE, row.disposition)
    }

    @Test
    fun `splits a trailing Amended clause into a revision note, leaving the condition core`() {
        val (core, note) =
            ErrorCodeYamlParser.splitRevisionNote(
                "Out of scope, shown in onboarding (C-2). Amended 25 Sep 2026; previously BLOCK, OVERRIDABLE",
            )
        assertEquals("Out of scope, shown in onboarding (C-2)", core)
        assertEquals("Amended 25 Sep 2026; previously BLOCK, OVERRIDABLE", note)
    }

    @Test
    fun `a condition with no Amended, Added or Extended clause has no revision note`() {
        val (core, note) = ErrorCodeYamlParser.splitRevisionNote("Unknown sourceSystem")
        assertEquals("Unknown sourceSystem", core)
        assertNull(note)
    }

    @Test
    fun `rejects a code not shaped AREA-NNN`() {
        val file =
            yamlFile(
                """
                error_codes:
                - code: ing-1
                  condition: bad
                  severity: BLOCK
                  disposition: MANUAL
                  owner: Platform
                """.trimIndent(),
            )
        assertThrows(IllegalStateException::class.java) { ErrorCodeYamlParser.parse(file) }
    }

    @Test
    fun `reads the pinned commit from a vendored file's header`() {
        val file =
            yamlFile(
                """
                # Vendored, read-only copy ..., pinned at commit dcd475a1d6ceb2b19bf53afcdd563da589bb9faf (2026-09-26)
                error_codes: []
                """.trimIndent(),
            )
        assertEquals("dcd475a1d6ceb2b19bf53afcdd563da589bb9faf", ErrorCodeYamlParser.readPinnedCommit(file))
    }

    private fun memberRow(
        disposition: String = "OVERRIDABLE",
        members: String,
    ) = yamlFile(
        "error_codes:\n- code: PAY-006\n  condition: c\n  severity: BLOCK\n  disposition: $disposition\n  owner: Finance\n$members",
    )

    private fun parseFails(file: java.io.File): String =
        assertThrows(IllegalStateException::class.java) { ErrorCodeYamlParser.parse(file) }.message!!

    @Test
    fun `a row with both members parses them`() {
        val row = ErrorCodeYamlParser.parse(memberRow(members = "  willTransmit: Sent as entered.\n  residualRisk: Possible rejection.\n")).single()
        assertEquals("Sent as entered.", row.willTransmit)
        assertEquals("Possible rejection.", row.residualRisk)
    }

    @Test
    fun `a row with neither member has both null`() {
        val row = ErrorCodeYamlParser.parse(memberRow(members = "")).single()
        assertNull(row.willTransmit)
        assertNull(row.residualRisk)
    }

    @Test
    fun `a row with only willTransmit fails naming its code`() {
        assertTrue(parseFails(memberRow(members = "  willTransmit: x\n")).contains("PAY-006"))
        assertTrue(parseFails(memberRow(members = "  residualRisk: x\n")).contains("PAY-006"))
    }

    @Test
    fun `a blank text fails`() {
        parseFails(memberRow(members = "  willTransmit: '   '\n  residualRisk: x\n"))
        parseFails(memberRow(members = "  willTransmit: x\n  residualRisk: ''\n"))
    }

    @Test
    fun `a non-string text fails`() {
        parseFails(memberRow(members = "  willTransmit: 5\n  residualRisk: x\n"))
    }

    @Test
    fun `a text with a line break fails`() {
        parseFails(memberRow(members = "  willTransmit: \"a\\nb\"\n  residualRisk: x\n"))
    }

    @Test
    fun `a text of MAX_TEXT_LENGTH plus one characters fails and exactly MAX_TEXT_LENGTH parses`() {
        val n = ErrorCodeYamlParser.MAX_TEXT_LENGTH
        parseFails(memberRow(members = "  willTransmit: ${"a".repeat(n + 1)}\n  residualRisk: x\n"))
        parseFails(memberRow(members = "  willTransmit: x\n  residualRisk: ${"a".repeat(n + 1)}\n"))
        val row = ErrorCodeYamlParser.parse(memberRow(members = "  willTransmit: ${"a".repeat(n)}\n  residualRisk: ${"b".repeat(n)}\n")).single()
        assertEquals(n, row.willTransmit!!.length)
        assertEquals(n, row.residualRisk!!.length)
    }

    @Test
    fun `a TERMINAL row with both members fails`() {
        assertTrue(parseFails(memberRow(disposition = "TERMINAL", members = "  willTransmit: x\n  residualRisk: y\n")).contains("PAY-006"))
    }
}
