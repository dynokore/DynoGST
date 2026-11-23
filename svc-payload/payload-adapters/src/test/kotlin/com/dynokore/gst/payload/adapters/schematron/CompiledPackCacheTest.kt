package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.gst.payload.domain.SchematronFile
import com.dynokore.gst.payload.domain.StagedPackMissingException
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CompiledPackCacheTest {
    private val svrl = "http://purl.oclc.org/dsdl/svrl"

    // Already-XSLT stylesheet: one failed-assert when the document root has no attribute `ok`.
    private fun stylesheet(marker: String) =
        """<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
             <xsl:template match="/*"><schematron-output xmlns="$svrl">
               <xsl:if test="not(@ok)"><failed-assert id="$marker" location="/*" test="@ok"><text>missing ok</text></failed-assert></xsl:if>
             </schematron-output></xsl:template></xsl:stylesheet>""".toByteArray()

    private class FakeSource(
        private val staged: Map<String, List<SchematronFile>>,
    ) : StagedPackSource {
        val calls = mutableListOf<String>()

        override fun fetch(packVersion: String): List<SchematronFile>? {
            calls += packVersion
            return staged[packVersion]
        }
    }

    private fun files(vararg v: Pair<String, String>) = v.map { SchematronFile(it.first, stylesheet(it.second)) }

    private fun one(marker: String) = files("a.sch" to marker)

    // The stylesheets of these cases are already XSLT, so the port passes the bytes through and counts its calls.
    private val portCalls = AtomicInteger()
    private val compiler =
        SchematronCompiler {
            portCalls.incrementAndGet()
            it
        }
    private val compilations = AtomicInteger()
    private val runner = SchematronRunner(CompilationListener { compilations.incrementAndGet() })
    private val bad = "<doc/>".toByteArray()
    private val good = "<doc ok='1'/>".toByteArray()

    @Test
    fun `INV-AC-1500 INV-AC-2325 one activation and 1000 validations cause exactly one compilation`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()), compiler)
        cache.activate("v1", one("R1"))
        repeat(1000) { cache.validate("v1", bad) }
        compilations.get() shouldBe 1
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("R1")
        cache.validate("v1", good).passed shouldBe true
    }

    @Test
    fun `a miss asks the source once and a held version never`() {
        val source = FakeSource(mapOf("v1" to one("R1"), "v2" to one("R2")))
        val cache = CompiledPackCache(runner, source, compiler)
        cache.activate("v1", one("R1"))
        cache.validate("v1", bad)
        source.calls shouldHaveSize 0
        repeat(3) { cache.validate("v2", bad) }
        source.calls shouldBe listOf("v2")
        compilations.get() shouldBe 2
    }

    @Test
    fun `two versions are held at once`() {
        val source = FakeSource(mapOf("v2" to one("R2")))
        val cache = CompiledPackCache(runner, source, compiler)
        cache.activate("v1", one("R1"))
        cache.validate("v2", bad).failedAsserts.map { it.id } shouldBe listOf("R2")
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("R1")
        cache.validate("v2", bad).failedAsserts.map { it.id } shouldBe listOf("R2")
        source.calls shouldBe listOf("v2")
    }

    @Test
    fun `a null from the source throws and the next validate asks the source again`() {
        val source = FakeSource(emptyMap())
        val cache = CompiledPackCache(runner, source, compiler)
        repeat(2) {
            val e = assertThrows(StagedPackMissingException::class.java) { cache.validate("v9", bad) }
            e.packVersion shouldBe "v9"
        }
        source.calls shouldBe listOf("v9", "v9")
        compilations.get() shouldBe 0
    }

    @Test
    fun `INV-AC-27228 failed asserts of two files come in ascending file-name order`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()), compiler)
        cache.activate("v1", files("b.sch" to "RB", "B.sch" to "RUP", "a.sch" to "RA"))
        // Byte order puts the uppercase name before the lowercase one; locale collation would not.
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("RUP", "RA", "RB")
        cache.validate("v1", good).passed shouldBe true
    }

    @Test
    fun `INV-AC-27228 a miss reads the staged source once and compiles each file once`() {
        val source = FakeSource(mapOf("v1" to files("b.sch" to "RB", "a.sch" to "RA")))
        val cache = CompiledPackCache(runner, source, compiler)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                (1..8).map {
                    pool.submit<List<String?>> {
                        cache.validate("v1", bad).failedAsserts.map { a ->
                            a.id
                        }
                    }
                }
            results.forEach { it.get() shouldBe listOf("RA", "RB") }
        } finally {
            pool.shutdown()
        }
        source.calls shouldBe listOf("v1")
        portCalls.get() shouldBe 2
        compilations.get() shouldBe 2
    }

    @Test
    fun `INV-AC-27228 an unknown version raises StagedPackMissingException`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()), compiler)
        assertThrows(StagedPackMissingException::class.java) { cache.validate("nope", bad) }.packVersion shouldBe "nope"
    }

    @Test
    fun `INV-AC-27228 activating a version again replaces its set`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()), compiler)
        cache.activate("v1", files("a.sch" to "R1", "b.sch" to "R2"))
        cache.activate("v1", files("c.sch" to "R3"))
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("R3")
    }

    @Test
    fun `INV-AC-27228 a stylesheet compiled at activation is not compiled again on validate`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()), compiler)
        cache.activate("v1", files("a.sch" to "R1", "b.sch" to "R2"))
        portCalls.get() shouldBe 2
        compilations.get() shouldBe 2
        repeat(50) { cache.validate("v1", bad) }
        portCalls.get() shouldBe 2
        compilations.get() shouldBe 2
    }
}
