package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.inbound.InboundEventStore
import com.dynokore.gst.canonical.application.netting.DeclaredCreditNotes
import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.gst.canonical.application.netting.NetDerivationDocuments
import com.dynokore.gst.canonical.application.netting.NetDerivationRecords
import com.dynokore.gst.canonical.application.netting.NetDerivedVersions
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.inbound.BuyerDisposition
import com.dynokore.gst.canonical.domain.inbound.Existing
import com.dynokore.gst.canonical.domain.inbound.FallbackKey
import com.dynokore.gst.canonical.domain.inbound.InboundEvent
import com.dynokore.gst.canonical.domain.inbound.InboundKind
import com.dynokore.gst.canonical.domain.inbound.UuidKey
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

/** INV-AC-28091: every version the record path writes also appends its `inbound_event` row, in the same call. */
class DocumentRowWriterInboundTest {
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val e1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val e2 = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
    private val tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private val calls = mutableListOf<String>()
    private val appended = mutableListOf<Triple<InboundEvent, UUID, Int>>()

    private val inserted = mutableListOf<NewDocument>()

    private val store =
        object : DocumentStore {
            override fun insert(document: NewDocument) {
                calls += "createFirst"
                inserted += document
            }

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                calls += "next"
                return UpdateOutcome.Updated(expectedVersion + 1L)
            }

            override fun rebuildDerived(
                docUuid: UUID,
                version: Int,
            ) = error("not used")

            override fun versionKeys(tenantId: UUID) = error("not used")

            override fun readWindow(
                tenantId: UUID,
                entityId: UUID,
                from: LocalDate,
                to: LocalDate,
                pageSize: Int,
                afterDocUuid: UUID?,
            ) = error("not used")
        }
    private val publisher =
        object : CanonicalVersionCreatedPublisher {
            override fun stage(facts: CanonicalVersionCreatedFacts) {
                calls += "stage"
            }
        }
    private val refs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = null

            override fun bind(
                docUuid: UUID,
                aggregateId: String,
            ) = true
        }
    private val versionWriter = CanonicalVersionWriter(store, publisher, refs)
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
                    ): NetDerivationFact? {
                        calls += "afterErpVersion"
                        return null
                    }
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
    private val declared =
        object : DeclaredCreditNotes {
            override fun of(
                command: RecordDocumentCommand,
                entityId: UUID,
            ): List<UUID> = emptyList()
        }
    private val inbound =
        object : InboundEventStore {
            override fun append(
                event: InboundEvent,
                docUuid: UUID,
                version: Int,
            ) {
                calls += "append"
                appended += Triple(event, docUuid, version)
            }

            override fun findByUuidKey(uuidKey: UuidKey): List<Existing> = error("not used")

            override fun findByFallbackKey(fallbackKey: FallbackKey): List<Existing> = error("not used")

            override fun previousDisposition(docUuid: UUID): BuyerDisposition? = error("not used")
        }
    private val rowWriter = DocumentRowWriter(versionWriter, rederivation, declared, inbound)

    private fun command(
        direction: Direction,
        function: DocumentFunction?,
        supplier: PartyIdentifier,
        number: String,
        path: RoutingNetworkPath = RoutingNetworkPath.NOT_ON_NETWORK,
    ) = RecordDocumentCommand(
        tenantId = tenant,
        entityRef = "E",
        sourceSystem = "S1",
        sourceKey = "K1",
        sourceRevision = 1,
        transactionDate = LocalDate.parse("2026-01-01"),
        direction = direction,
        supplier = supplier,
        buyer = PartyIdentifier("UEN", "BUY-1"),
        invoiceBacked = true,
        documentNumber = number,
        networkPath = path,
        documentFunction = function,
    )

    private fun facts(
        entity: UUID,
        on: String,
        verdict: ScopeVerdict? = ScopeVerdict.IN_SCOPE,
    ) = DocumentRowFacts(
        entity,
        null,
        380,
        "CTP-118",
        2026,
        null,
        UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        DocumentState.INGESTED,
        "2026-Q1",
        emptyList(),
        NewVersion("R", verdict, "S-1", "M-1", null, null, LocalDate.parse(on), "user-1"),
    )

    private val sale = command(Direction.SALE, DocumentFunction.CREDIT_NOTE, PartyIdentifier("S1", "V1"), "CN-1")
    private val purchase =
        command(Direction.PURCHASE, DocumentFunction.INVOICE, PartyIdentifier("0195", "ABC-1"), "PI-9")

    @Test
    fun `INV-AC-28728 the record command path hands the command's entityRef to the store`() {
        rowWriter.write(sale.copy(entityRef = "ENT-CMD-7"), d1, 1, facts(e1, "2026-03-05"))
        inserted.single().entityRef shouldBe "ENT-CMD-7"
    }

    @Test
    fun `INV-AC-28091 C1 a SALE version 1 appends one POSTING event, fallback key empty, after the writer`() {
        rowWriter.write(sale, d1, 1, facts(e1, "2026-03-05"))
        calls shouldBe listOf("createFirst", "stage", "append")
        appended shouldBe
            listOf(
                Triple(
                    InboundEvent(
                        kind = InboundKind.POSTING,
                        entityId = e1,
                        documentFunction = DocumentFunction.CREDIT_NOTE,
                        supplierScheme = "",
                        supplierValue = "",
                        documentNumber = "CN-1",
                        transactionDate = LocalDate.parse("2026-03-05"),
                        supplierEndpointId = null,
                        supplierDocUuid = null,
                        buyerDisposition = null,
                    ),
                    d1,
                    1,
                ),
            )
    }

    @Test
    fun `INV-AC-28091 C2 a PURCHASE version 2 appends the supplier after next and before the re-derivation`() {
        rowWriter.write(purchase, d2, 2, facts(e2, "2026-07-01"))
        calls shouldBe listOf("next", "stage", "append", "afterErpVersion")
        appended shouldBe
            listOf(
                Triple(
                    InboundEvent(
                        kind = InboundKind.POSTING,
                        entityId = e2,
                        documentFunction = DocumentFunction.INVOICE,
                        supplierScheme = "0195",
                        supplierValue = "ABC-1",
                        documentNumber = "PI-9",
                        transactionDate = LocalDate.parse("2026-07-01"),
                    ),
                    d2,
                    2,
                ),
            )
    }

    @Test
    fun `INV-AC-28091 C3 a network receipt throws IllegalStateException and calls neither the writer nor the store`() {
        val receipt = purchase.copy(networkPath = RoutingNetworkPath.RECEIVED_VIA_NETWORK)
        assertThrows<IllegalStateException> { rowWriter.write(receipt, d2, 1, facts(e2, "2026-07-01")) }
        calls shouldBe emptyList()
        appended shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28091 C4 the unannounced path appends once`() {
        rowWriter.write(sale, d1, 1, facts(e1, "2026-03-05", verdict = null))
        calls shouldBe listOf("createFirst", "append")
        appended.single().first.documentFunction shouldBe DocumentFunction.CREDIT_NOTE
    }
}
