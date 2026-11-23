package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** `JacksonIdentityTripleDocuments` (INV-AC-26792 case 11): the triple is read and replaced, nothing else changes. */
class JacksonIdentityTripleDocumentsTest {
    private val adapter = JacksonIdentityTripleDocuments()
    private val mapper = JsonMapper.builder().build()
    private val old = SupplierIdentity("S-1", "SUP-1", "M1-0000001-X")
    private val next = SupplierIdentity("S-2", "SUP-2", "M2-0000002-Y")

    private fun json(triple: String) =
        """{"documentTypeCode":{"value":"380","classification":"NONE"},$triple""" +
            """"lines":{"value":[{"lineNumber":1}],"classification":"NONE"},"currency":{"value":"SGD"}}"""

    private val stored =
        json(
            """"identityTriple":{"value":{"senderId":"S-1","supplierId":"SUP-1","supplierGstn":"M1-0000001-X"},""" +
                """"classification":"IDENTIFYING"},""",
        )

    @Test
    fun `INV-AC-26792 case 11 tripleOf returns the stored triple`() {
        adapter.tripleOf(stored) shouldBe old
    }

    @Test
    fun `INV-AC-26792 case 11 tripleOf returns null for a JSON without identityTriple or with a partial one`() {
        adapter.tripleOf(json("")) shouldBe null
        adapter.tripleOf(json(""""identityTriple":{"value":{"senderId":"S-1"}},""")) shouldBe null
    }

    @Test
    fun `INV-AC-26792 case 11 withTriple changes the identityTriple value only`() {
        val after = mapper.readTree(adapter.withTriple(stored, next))
        val before = mapper.readTree(stored)
        adapter.tripleOf(after.toString()) shouldBe next
        after.path("identityTriple").path("classification") shouldBe
            before.path("identityTriple").path("classification")
        after.propertyNames().toList() shouldBe before.propertyNames().toList()
        before.propertyNames().toList().filter { it != "identityTriple" }.forEach { member ->
            after.path(member) shouldBe before.path(member)
        }
    }
}
