package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.SchematronCompileException
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class SchXslt2CompilerTest {
    private fun resource(name: String): ByteArray =
        SchXslt2CompilerTest::class.java.getResourceAsStream("/schematron/$name")!!.use { it.readBytes() }

    private val compiler = SchXslt2Compiler()

    @Test
    fun `INV-AC-27226 a violated assert is reported with the id of the source`() {
        val stylesheet = compiler.compile(resource("one-assert.sch"))
        val outcome = SchematronRunner().compile(stylesheet).validate(resource("one-assert-fail.xml"))
        outcome.failedAsserts.size shouldBe 1
        outcome.failedAsserts.single().id shouldBe "DOC-01"
    }

    @Test
    fun `INV-AC-27226 a satisfied document reports no failed assert`() {
        val stylesheet = compiler.compile(resource("one-assert.sch"))
        SchematronRunner()
            .compile(stylesheet)
            .validate(resource("one-assert-pass.xml"))
            .failedAsserts
            .shouldBeEmpty()
    }

    @Test
    fun `INV-AC-27226 an include of an external file is refused and nothing is fetched`() {
        assertThrows<SchematronCompileException> { compiler.compile(resource("external-include.sch")) }
        val calls = AtomicInteger()
        ServerSocket(0).use { server ->
            val thread = Thread { runCatching { while (true) server.accept().use { calls.incrementAndGet() } } }
            thread.isDaemon = true
            thread.start()
            val source =
                String(resource("external-include.sch"), Charsets.UTF_8)
                    .replace("127.0.0.1:1", "127.0.0.1:${server.localPort}")
            assertThrows<SchematronCompileException> { compiler.compile(source.toByteArray()) }
            val entity =
                """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "http://127.0.0.1:${server.localPort}/e">]>""" +
                    """<sch:schema xmlns:sch="http://purl.oclc.org/dsdl/schematron"><sch:title>&e;</sch:title></sch:schema>"""
            assertThrows<SchematronCompileException> { compiler.compile(entity.toByteArray()) }
        }
        calls.get() shouldBe 0
    }

    @Test
    fun `INV-AC-27226 a file that is not XML is refused`() {
        assertThrows<SchematronCompileException> { compiler.compile("this is not xml".toByteArray()) }
        assertThrows<SchematronCompileException> { compiler.compile("<a/>".toByteArray()) }
    }

    @Test
    fun `INV-AC-27226 two compilations of one input are byte-equal`() {
        val first = compiler.compile(resource("one-assert.sch"))
        val second = SchXslt2Compiler().compile(resource("one-assert.sch"))
        second.contentEquals(first) shouldBe true
    }
}
