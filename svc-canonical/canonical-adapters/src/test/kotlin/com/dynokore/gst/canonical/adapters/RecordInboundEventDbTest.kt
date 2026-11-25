package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentAggregateRefs
import com.dynokore.gst.canonical.application.document.DocumentRowFacts
import com.dynokore.gst.canonical.application.document.DocumentRowWriter
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.gst.canonical.application.netting.NetDerivationDocuments
import com.dynokore.gst.canonical.application.netting.NetDerivationRecords
import com.dynokore.gst.canonical.application.netting.NetDerivedVersions
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.source.SourceKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-28091: the document row and the `inbound_event` row of a record transaction commit or roll back together. */
class RecordInboundEventDbTest {
    private val kit = DocumentKit()
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    private val noPublisher =
        object : CanonicalVersionCreatedPublisher {
            override fun stage(facts: CanonicalVersionCreatedFacts) = error("unannounced versions stage nothing")
        }
    private val noRefs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = null

            override fun bind(
                docUuid: UUID,
                aggregateId: String,
            ) = true
        }
    private val versionWriter = CanonicalVersionWriter(kit.store, noPublisher, noRefs)
    private val declared =
        object : DeclaredCreditNotes {
            override fun of(
                command: RecordDocumentCommand,
                entityId: UUID,
            ): List<UUID> = emptyList()
        }

    /** Version 1 only is written here, so the re-derivation is never reached. */
    private val rederivation =
        NetRederivation(
            records =
                object : NetDerivationRecords {
                    override fun record(
                        docUuid: UUID,
                        version: Int,
                        baseVersion: Int,
                        creditNotes: List<NetCreditNoteRef>,
                    ) = error("not used")

                    override fun recordOf(
                        docUuid: UUID,
                        version: Int,
                    ): NetDerivationFact? = error("not used")
                },
            documents =
                object : NetDerivationDocuments {
                    override fun currentVersion(docUuid: UUID) = error("not used")

                    override fun base(
                        docUuid: UUID,
                        version: Int,
                    ) = error("not used")

                    override fun creditNote(
                        docUuid: UUID,
                        version: Int,
                    ) = error("not used")
                },
            versions =
                object : NetDerivedVersions {
                    override fun build(
                        docUuid: UUID,
                        baseVersion: Int,
                        derived: Derived,
                    ) = error("not used")
                },
            writer = versionWriter,
            factsFor = { _, _ -> error("not used") },
        )
    private val rowWriter = DocumentRowWriter(versionWriter, rederivation, declared, kit.inbound)

    private fun command(number: String) =
        RecordDocumentCommand(
            tenantId = t1,
            entityRef = "E",
            sourceSystem = "S1",
            sourceKey = "K-$number",
            sourceRevision = 1,
            transactionDate = day,
            direction = Direction.SALE,
            supplier = PartyIdentifier("S1", "V1"),
            buyer = PartyIdentifier("UEN", "BUY-1"),
            invoiceBacked = true,
            documentNumber = number,
            documentFunction = DocumentFunction.CREDIT_NOTE,
        )

    private fun facts(number: String) =
        DocumentRowFacts(
            entity,
            null,
            380,
            "CTP-118",
            2026,
            null,
            null,
            DocumentState.INGESTED,
            "",
            emptyList(),
            NewVersion(DocumentKit.defaultCanonical(number), null, null, null, null, null, day, "svc"),
        )

    private fun count(
        table: String,
        doc: UUID,
    ) = DocumentSql.one("select count(*)::text from canonical.$table where doc_uuid = ?", doc)!!.toInt()

    private fun record(
        doc: UUID,
        number: String,
        failAfter: Boolean,
    ) {
        val command = command(number)
        SourceLinks.seed(
            SourceKey(command.tenantId, command.sourceSystem, command.sourceKey, command.sourceRevision),
            doc,
        )
        kit.asTenant(t1) {
            kit.transaction.inTransaction {
                rowWriter.write(command, doc, 1, facts(number))
                if (failAfter) error("record failed after the writes")
            }
        }
    }

    @Test
    fun `INV-AC-28091 commit keeps the document row and the POSTING inbound row, tenant T2 sees none`() {
        val d3 = UUID.randomUUID()
        record(d3, "CN-D3", failAfter = false)
        count("document", d3) shouldBe 1
        count("inbound_event", d3) shouldBe 1
        DocumentSql
            .rows(
                "select event_kind, document_function, version::text from canonical.inbound_event where doc_uuid = ?",
                d3,
            ).single() shouldBe listOf("POSTING", "CREDIT_NOTE", "1")
        kit.asTenant(t2) {
            kit.transaction.inTransaction {
                kit.dsl.fetchCount(INBOUND_EVENT) shouldBe 0
            }
        }
    }

    @Test
    fun `INV-AC-28091 a throw before the commit leaves neither the document row nor the inbound row`() {
        val d4 = UUID.randomUUID()
        shouldThrow<IllegalStateException> { record(d4, "CN-D4", failAfter = true) }
        count("document", d4) shouldBe 0
        count("inbound_event", d4) shouldBe 0
    }
}
