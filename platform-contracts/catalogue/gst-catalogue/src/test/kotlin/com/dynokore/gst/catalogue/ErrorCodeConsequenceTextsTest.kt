package com.dynokore.gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-T-26327 (INV-REQ-943): the error catalogue is the source of the two consequence texts that
 * `getOverridePreview` answers, so every OVERRIDABLE code has both and no other code has either.
 */
class ErrorCodeConsequenceTextsTest {
    /** The maxLength of `OverridePreview.willTransmit` and `residualRisk` in console-bff/openapi.yaml. */
    private val schemaBound = 1000

    private val codesWithTexts = listOf("MDM-003", "PAY-006", "PAY-011", "PER-001", "RUL-007", "SUB-015")

    @Test
    fun `every OVERRIDABLE code has two different texts within the schema bound`() {
        for (entry in ErrorCode.entries.filter { it.disposition == Disposition.OVERRIDABLE }) {
            for ((member, text) in listOf("willTransmit" to entry.willTransmit, "residualRisk" to entry.residualRisk)) {
                assertNotNull(text, "${entry.code} $member")
                assertTrue(text.orEmpty().isNotBlank(), "${entry.code} $member is blank")
                assertTrue(text.orEmpty().length <= schemaBound, "${entry.code} $member is longer than $schemaBound")
            }
            assertNotEquals(entry.willTransmit, entry.residualRisk, entry.code)
        }
    }

    @Test
    fun `no other code has a text`() {
        for (entry in ErrorCode.entries.filter { it.disposition != Disposition.OVERRIDABLE }) {
            assertNull(entry.willTransmit, "${entry.code} willTransmit")
            assertNull(entry.residualRisk, "${entry.code} residualRisk")
        }
    }

    @Test
    fun `the codes with the texts are the six overridable codes`() {
        val withAnyText = ErrorCode.entries.filter { it.willTransmit != null || it.residualRisk != null }
        assertEquals(codesWithTexts, withAnyText.map { it.code }.sorted())
    }

    @Test
    fun `the generated MDM-003 willTransmit is the text of the vendored row`() {
        assertEquals(vendoredPlainScalar("MDM-003", "willTransmit"), ErrorCode.MDM_003.willTransmit)
    }

    /**
     * The value of [member] in the row of [code] of the vendored YAML. This module has no YAML
     * library, so only a one-line plain scalar is read and every other form fails the test.
     */
    private fun vendoredPlainScalar(
        code: String,
        member: String,
    ): String {
        val lines = File("../errors/error-codes.yaml").readLines()
        val start = lines.indices.single { lines[it] == "- code: $code" }
        val row = lines.drop(start + 1).takeWhile { it.startsWith("  ") }
        val index = row.indices.single { row[it].startsWith("  $member: ") }
        val value = row[index].removePrefix("  $member: ")
        assertTrue(value.first() !in "\"'|>&*!{[", "$code $member is not a plain scalar")
        assertTrue(" #" !in value, "$code $member holds a YAML comment")
        assertTrue(row.getOrNull(index + 1)?.startsWith("   ") != true, "$code $member continues on a second line")
        return value.trim()
    }
}
