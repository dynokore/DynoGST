package com.dynokore.gst.payload.adapters.schematron

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

class StagedSchematronSourceTest {
    private fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun entry(
        name: String,
        content: ByteArray,
        sha256: String = sha(content),
    ) = """{"name":"$name","sha256":"$sha256","content":"${Base64.getEncoder().encodeToString(content)}"}"""

    private fun contents(vararg entries: String) = """{"version":"v1","files":[${entries.joinToString(",")}]}"""

    private fun refused(text: String) =
        assertThrows(IllegalStateException::class.java) {
            StagedSchematronSource.parse(text)
        }

    @Test
    fun `INV-AC-27228 a valid contents text gives the files byte for byte`() {
        val a = byteArrayOf(0, 1, 2, -1, 127)
        val b = "<sch/>".toByteArray()
        val files = StagedSchematronSource { contents(entry("a.sch", a), entry("b.sch", b)) }.fetch("v1")!!
        files.map { it.name } shouldBe listOf("a.sch", "b.sch")
        files[0].content.toList() shouldBe a.toList()
        files[1].content.toList() shouldBe b.toList()
    }

    @Test
    fun `INV-AC-27228 a sha256 that differs from the content is refused`() {
        refused(contents(entry("a.sch", "x".toByteArray()), entry("b.sch", "y".toByteArray(), sha("z".toByteArray()))))
    }

    @Test
    fun `INV-AC-27228 a duplicate file name is refused`() {
        refused(contents(entry("a.sch", "x".toByteArray()), entry("a.sch", "y".toByteArray())))
    }

    @Test
    fun `INV-AC-27228 an empty files array is refused`() {
        refused("""{"version":"v1","files":[]}""")
    }

    @Test
    fun `INV-AC-27228 a text that is not JSON is refused`() {
        refused("not json {")
    }

    @Test
    fun `INV-AC-27228 a missing version gives null`() {
        StagedSchematronSource { null }.fetch("v9") shouldBe null
    }
}
