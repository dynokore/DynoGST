package com.dynokore.gst.ingestion.acceptance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * INV-AC-26631 (INV-REQ-1287): validation and hold on ingestion read no pre-issuance mode (VP-26). A text scan of the
 * production sources of the four ingestion modules that hold Kotlin source; the scan fails closed on a missing or
 * empty directory.
 */
class PreIssuanceModeIsolationTest {
    // The Gradle root project, as ArchitectureTest's Konsist directories resolve.
    private val root = File(System.getProperty("gst.root.dir") ?: "..").absoluteFile.normalize()
    private val sourceDirs = listOf("domain", "application", "adapters", "bootstrap").map { "ingestion-$it/src/main" }
    private val texts = listOf("VP-26", "vp-26", "PreIssuanceMode", "preIssuanceMode")

    private fun kotlinFiles(dir: String): List<File> {
        val base = File(root, dir)
        check(base.isDirectory) { "scan directory does not exist: $dir" }
        val files = base.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        check(files.isNotEmpty()) { "scan directory holds no .kt file: $dir" }
        return files
    }

    /** Relative paths of the `.kt` files under [dirs] that contain any of [needles] (exact, case-sensitive). */
    private fun scan(
        dirs: List<String>,
        needles: List<String> = texts,
    ): Set<String> =
        dirs
            .flatMap { kotlinFiles(it) }
            .filter { f -> f.readText().let { body -> needles.any { body.contains(it) } } }
            .map { it.relativeTo(root).path }
            .toSet()

    @Test
    fun `INV-AC-26631 I1 the four real source directories are non-empty and hold no pre-issuance mode text`() {
        sourceDirs.forEach { assertTrue(kotlinFiles(it).isNotEmpty(), it) }
        assertEquals(emptySet<String>(), scan(sourceDirs))
    }

    private fun probe(
        body: String,
        needles: List<String> = texts,
    ): Pair<Set<String>, String> {
        val rel = "acceptance/build/probes/${UUID.randomUUID()}"
        val dir = File(root, rel)
        dir.mkdirs()
        try {
            File(dir, "Probe.kt").writeText("package probe\n\nval x = \"$body\"\n")
            return scan(listOf(rel), needles) to "$rel/Probe.kt"
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `INV-AC-26631 I2 each of the four texts in a val initializer is reported`() {
        texts.forEach { text ->
            val (found, expected) = probe(text)
            assertEquals(setOf(expected), found, text)
        }
    }

    @Test
    fun `INV-AC-26631 I2 look-alike texts are not reported`() {
        listOf("VP-27", "vp-27", "preissuancemode", "PreIssuance Mode").forEach { text ->
            assertEquals(emptySet<String>(), probe(text).first, text)
        }
    }

    @Test
    fun `INV-AC-26631 I3 a missing directory or one without a kt file fails closed`() {
        assertThrows(IllegalStateException::class.java) { scan(listOf("acceptance/build/probes/${UUID.randomUUID()}")) }
        val rel = "acceptance/build/probes/${UUID.randomUUID()}"
        val dir = File(root, rel).also { it.mkdirs() }
        try {
            assertThrows(IllegalStateException::class.java) { scan(listOf(rel)) }
            File(dir, "notes.txt").writeText("VP-26")
            assertThrows(IllegalStateException::class.java) { scan(listOf(rel)) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
