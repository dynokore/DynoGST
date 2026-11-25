package com.dynokore.gst.payload.adapters.build

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SvrlLocationTest {
    private val ns = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    private val cbc = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    private val doc =
        """<Invoice xmlns="$ns" xmlns:cbc="$cbc"><cbc:ID>INV-1</cbc:ID><cbc:Note>a</cbc:Note><cbc:Note>b</cbc:Note></Invoice>"""
            .toByteArray()

    @Test
    fun `INV-AC-27232 the last step of a plain location`() {
        SvrlLocation.lastStepName("/Invoice/ID") shouldBe "ID"
        SvrlLocation.lastStepName("/Invoice") shouldBe "Invoice"
    }

    @Test
    fun `INV-AC-27232 predicates and prefixes are dropped`() {
        SvrlLocation.lastStepName("/*:Invoice[namespace-uri()='$ns'][1]/*:ID[1]") shouldBe "ID"
        SvrlLocation.lastStepName("/Q{$ns}Invoice[1]/Q{$cbc}Note[2]") shouldBe "Note"
        SvrlLocation.lastStepName("/cbc:Invoice/cbc:ID") shouldBe "ID"
    }

    @Test
    fun `INV-AC-27232 a slash inside a namespace-uri predicate does not split the step`() {
        SvrlLocation.lastStepName("/*:Invoice[1]/*:ID[namespace-uri()='urn:x/y/z'][1]") shouldBe "ID"
        SvrlLocation.lastStepName("/*:Invoice[1]/*[local-name()='ID' and namespace-uri()=\"http://a/b\"]") shouldBe "*"
        SvrlLocation.lastStepName("/Q{http://a/b}Invoice[1]") shouldBe "Invoice"
    }

    @Test
    fun `INV-AC-27232 the value of the selected node`() {
        SvrlLocation.valueOf(doc, "/*:Invoice[namespace-uri()='$ns'][1]/*:ID[namespace-uri()='$cbc'][1]") shouldBe
            "INV-1"
        SvrlLocation.valueOf(doc, "/Q{$ns}Invoice[1]/Q{$cbc}Note[2]") shouldBe "b"
    }

    @Test
    fun `INV-AC-27232 an unselected node gives the empty string`() {
        SvrlLocation.valueOf(doc, "/Q{$ns}Invoice[1]/Q{$cbc}Missing[1]") shouldBe ""
    }

    @Test
    fun `INV-AC-27232 an external entity in the payload is not resolved`() {
        val dir = Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "xxe").toFile()
        try {
            val secret = File(dir, "secret.txt").also { it.writeText("TOP-SECRET-CONTENT") }
            val xml =
                """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "${secret.toURI()}">]><r><a>&x;</a></r>"""
                    .toByteArray()
            val value = runCatching { SvrlLocation.valueOf(xml, "/r/a") }.getOrDefault("")
            value shouldNotContain "TOP-SECRET"
        } finally {
            dir.deleteRecursively()
        }
    }
}
