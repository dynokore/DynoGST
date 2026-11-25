package com.dynokore.gst.canonical.adapters

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The store writes `document.entity_ref` of a new document, over the runtime login. */
class DocumentEntityRefDbTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    @Test
    fun `INV-AC-28727 a document created with an entity reference stores exactly that text`() {
        val document = DocumentKit.document(tenant, entity, day).copy(entityRef = "ENT-001")
        kit.insert(document)

        val stored = DocumentSql.documentColumn("entity_ref", document.docUuid)

        stored shouldBe "ENT-001"
        stored shouldNotBe "ENT-0011"
    }

    @Test
    fun `INV-AC-28727 a document created without an entity reference stores null`() {
        val document = DocumentKit.document(tenant, entity, day)
        kit.insert(document)

        DocumentSql.documentColumn("entity_ref", document.docUuid) shouldBe null
    }
}
