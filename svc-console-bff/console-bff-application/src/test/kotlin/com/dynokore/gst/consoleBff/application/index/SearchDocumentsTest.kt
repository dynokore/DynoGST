package com.dynokore.gst.consoleBff.application.index

import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.gst.consoleBff.domain.index.DocumentSearchCriteria
import com.dynokore.gst.consoleBff.domain.index.IndexState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SearchDocumentsTest {
    private data class ReadCall(
        val tenantId: String,
        val entities: Set<String>,
        val criteria: DocumentSearchCriteria,
        val limit: Int,
        val cursor: String?,
    )

    private data class AskCall(
        val tenantId: String,
        val principalId: String,
        val key: String,
    )

    private class Fakes(
        val granted: Set<String> = setOf("E1", "E2"),
        val answers: Map<String, SearchDecision> = emptyMap(),
    ) : EntityScope,
        DocumentSearchDecisions,
        DocumentIndexReads {
        val reads = mutableListOf<ReadCall>()
        val asks = mutableListOf<AskCall>()
        val hit = DocumentSearchHit("D1", "E1", "N1", 1, IndexState.READY, null, null)

        override fun entitiesOf(
            tenantId: String,
            principalId: String,
        ) = granted

        override fun decide(
            tenantId: String,
            principalId: String,
            permissionKey: String,
        ): SearchDecision {
            asks += AskCall(tenantId, principalId, permissionKey)
            return answers[permissionKey] ?: SearchDecision.ALLOW
        }

        override fun search(
            tenantId: String,
            entityRefs: Set<String>,
            criteria: DocumentSearchCriteria,
            limit: Int,
            cursor: String?,
        ): DocumentSearchPage {
            reads += ReadCall(tenantId, entityRefs, criteria, limit, cursor)
            return DocumentSearchPage(listOf(hit), "next")
        }
    }

    private fun run(
        f: Fakes,
        c: DocumentSearchCriteria,
    ) = SearchDocuments(f, f, f, maxLimit = 100, defaultLimit = 25)
        .search(SearchDocumentsQuery("T1", "U1", c))

    @Test
    fun `INV-AC-26809 no criterion reads the whole grant scope with the default limit and decides nothing`() {
        val f = Fakes()
        val c = DocumentSearchCriteria()
        val r = run(f, c)
        f.reads shouldBe listOf(ReadCall("T1", setOf("E1", "E2"), c, 25, null))
        f.asks shouldBe emptyList()
        (r as SearchDocumentsResult.Found).page.items shouldBe listOf(f.hit)
    }

    @Test
    fun `INV-AC-26809 entityRef outside the scope answers the empty page without a read`() {
        val f = Fakes()
        val r = run(f, DocumentSearchCriteria(entityRef = "E3"))
        f.reads shouldBe emptyList()
        r shouldBe SearchDocumentsResult.Found(DocumentSearchPage(emptyList(), null))
    }

    @Test
    fun `INV-AC-26809 entityRef inside the scope narrows the read to that entity`() {
        val f = Fakes()
        val c = DocumentSearchCriteria(entityRef = "E1", limit = 10, cursor = "cur")
        run(f, c)
        f.reads shouldBe listOf(ReadCall("T1", setOf("E1"), c, 10, "cur"))
    }

    @Test
    fun `INV-AC-26809 an empty grant scope reads nothing`() {
        val f = Fakes(granted = emptySet())
        run(f, DocumentSearchCriteria())
        f.reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26809 counterparty with doc view content deny is forbidden and not read`() {
        val f = Fakes(answers = mapOf("doc.view.content" to SearchDecision.DENY))
        run(f, DocumentSearchCriteria(counterparty = "ACME")) shouldBe SearchDocumentsResult.Forbidden
        f.reads shouldBe emptyList()
        f.asks shouldBe listOf(AskCall("T1", "U1", "doc.view.content"))
    }

    @Test
    fun `INV-AC-26809 counterparty with a degraded replica is forbidden and not read`() {
        val f = Fakes(answers = mapOf("doc.view.content" to SearchDecision.DEGRADED))
        run(f, DocumentSearchCriteria(counterparty = "ACME")) shouldBe SearchDocumentsResult.Forbidden
        f.reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26809 acknowledgementId with its key deny is forbidden and not read`() {
        val f = Fakes(answers = mapOf("enquiry-lookup.by-acknowledgement" to SearchDecision.DENY))
        run(f, DocumentSearchCriteria(acknowledgementId = "ACK1")) shouldBe SearchDocumentsResult.Forbidden
        f.reads shouldBe emptyList()
        f.asks shouldBe listOf(AskCall("T1", "U1", "enquiry-lookup.by-acknowledgement"))
    }

    @Test
    fun `INV-AC-26809 a limit above the maximum is refused without decision or read`() {
        val f = Fakes()
        run(f, DocumentSearchCriteria(limit = 101, counterparty = "A")) shouldBe SearchDocumentsResult.BadLimit
        run(f, DocumentSearchCriteria(limit = 0)) shouldBe SearchDocumentsResult.BadLimit
        f.reads shouldBe emptyList()
        f.asks shouldBe emptyList()
        run(f, DocumentSearchCriteria(limit = 100))
        f.reads.single().limit shouldBe 100
    }

    @Test
    fun `INV-AC-26809 each distinct key is decided once per request with the principal and tenant`() {
        val f = Fakes()
        val c =
            DocumentSearchCriteria(
                counterparty = "A",
                amountFrom = BigDecimal("1"),
                amountTo = BigDecimal("2"),
                acknowledgementId = "ACK1",
            )
        run(f, c)
        f.asks.toSet() shouldBe
            setOf(AskCall("T1", "U1", "doc.view.content"), AskCall("T1", "U1", "enquiry-lookup.by-acknowledgement"))
        f.asks.size shouldBe 2
        f.reads.size shouldBe 1
    }

    @Test
    fun `INV-AC-26809 the other criteria need no decision`() {
        val f = Fakes()
        run(f, DocumentSearchCriteria(documentNumber = "N1", docUuid = "D1", categoryCode = "SR", errorCode = "E"))
        f.asks shouldBe emptyList()
    }
}
