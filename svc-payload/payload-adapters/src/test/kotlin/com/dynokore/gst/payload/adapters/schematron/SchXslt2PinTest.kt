package com.dynokore.gst.payload.adapters.schematron

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SchXslt2 Schematron compiler library is pinned once in the version catalogue, locked in payload-adapters and
 * present in the dependency verification metadata (INV-T-27225, INV-REQ-472). Tests run from the module directory.
 */
class SchXslt2PinTest {
    private val group = "name.dmaus.schxslt"
    private val artifact = "schxslt2"
    private val root: Path = Path.of("..").toAbsolutePath().normalize()

    private fun lines(relative: String): List<String> = Files.readAllLines(root.resolve(relative))

    private val libraryLines: List<String> =
        lines("gradle/libs.versions.toml").filter {
            it.trimStart().startsWith("#").not() &&
                it.contains("\"$group:$artifact\"")
        }

    private val versionRef: String =
        Regex("""version\.ref\s*=\s*"([^"]+)"""").find(libraryLines.single())!!.groupValues[1]

    private val versionLine = Regex("""^\s*${Regex.escape(versionRef)}\s*=\s*"([^"]+)"\s*$""")

    private val pinned: String =
        lines("gradle/libs.versions.toml")
            .mapNotNull { versionLine.find(it)?.groupValues?.get(1) }
            .single()

    @Test
    fun `INV-AC-27224 the catalogue names the library once`() {
        libraryLines.size shouldBe 1
        libraryLines.single().trim() shouldBe
            """$artifact = { module = "$group:$artifact", version.ref = "$versionRef" }"""
    }

    @Test
    fun `INV-AC-27224 the lock file of payload-adapters lists the pinned version`() {
        val prefix = "$group:$artifact:"
        val entries = lines("payload-adapters/gradle.lockfile").filter { it.startsWith(prefix) }
        entries.size shouldBe 1
        entries.single().substringBefore('=') shouldBe "$prefix$pinned"
    }

    @Test
    fun `INV-AC-27224 the verification metadata has a component for the pinned version`() {
        val component = """<component group="$group" name="$artifact" version="$pinned">"""
        lines("gradle/verification-metadata.xml").count { it.trim() == component } shouldBe 1
    }
}
