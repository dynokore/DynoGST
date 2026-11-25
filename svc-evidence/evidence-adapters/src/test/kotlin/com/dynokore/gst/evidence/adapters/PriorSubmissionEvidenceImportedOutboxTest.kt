package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.PriorSubmissionEvidenceImportedOutbox
import com.dynokore.gst.evidence.adapters.persistence.JooqPriorEvidenceStore
import com.dynokore.gst.evidence.application.migration.PriorEvidenceImported
import com.dynokore.gst.evidence.application.migration.PriorEvidenceRow
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-28460: one event per newly imported record reaches the outbox in the caller's transaction. */
class PriorSubmissionEvidenceImportedOutboxTest {
    private val tenant = UUID.randomUUID()
    private val json = JsonMapper.builder().build()
    private val type = PriorSubmissionEvidenceImportedOutbox.EVENT_TYPE
    private val clock = Clock.fixed(Instant.parse("2026-11-25T08:30:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 2, 27)

    private fun writer(connection: Connection) =
        JdbcOutboxWriter(
            DSL.using(connection, SQLDialect.POSTGRES),
            EvidenceDb.SCHEMA,
            "evidence-svc",
            "instance-1",
            EventCatalogue(
                listOf(
                    CatalogueEntry(type, "gst.evidence.prior-submission-evidence-imported.v1", setOf("evidence-svc")),
                ),
            ),
            clock,
        )

    private fun event(document: String) = PriorEvidenceImported("E1", document, date, "ACK-$document", "LEGACY")

    private fun row(document: String) =
        PriorEvidenceRow(
            UUID.randomUUID(),
            "E1",
            "2026-Q1",
            document,
            date,
            "ACK-$document",
            "LEGACY",
            null,
            clock.instant(),
            "u-importer",
        )

    /** Inserts each document with its event in one transaction, then commits or rolls back. */
    private fun importAll(
        documents: List<String>,
        commit: Boolean,
    ) = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
        connection.autoCommit = false
        SignedBindings.bind(connection, tenant)
        val store = JooqPriorEvidenceStore(DSL.using(connection, SQLDialect.POSTGRES))
        val outbox = PriorSubmissionEvidenceImportedOutbox(writer(connection))
        documents.forEach {
            store.insert(TenantId(tenant), row(it))
            outbox.imported(TenantId(tenant), event(it))
        }
        if (commit) connection.commit() else connection.rollback()
    }

    private class Stored(
        val type: String,
        val data: String,
        val entity: String,
        val tenant: String,
    )

    private fun rows(): List<Stored> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select type, data::text, entity_ref, tenant_id::text from ${EvidenceDb.SCHEMA}.outbox " +
                        "where tenant_id = ? order by seq",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                Stored(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun priorRows(): String =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select count(*) from evidence.prior_evidence where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    @Test
    fun `INV-AC-28460 one call appends one outbox row of the type with the five members, the tenant and the entity`() {
        importAll(listOf("DOC-1"), commit = true)
        val stored = rows().single()
        stored.type shouldBe type
        stored.tenant shouldBe tenant.toString()
        stored.entity shouldBe "E1"
        val payload = json.readTree(stored.data)
        payload.propertyNames().asSequence().toSet() shouldBe
            setOf("entityRef", "documentNumber", "transactionDate", "acknowledgementRef", "priorSolution")
        payload.get("entityRef").asString() shouldBe "E1"
        payload.get("documentNumber").asString() shouldBe "DOC-1"
        payload.get("transactionDate").asString() shouldBe "2026-02-27"
        payload.get("acknowledgementRef").asString() shouldBe "ACK-DOC-1"
        payload.get("priorSolution").asString() shouldBe "LEGACY"
        priorRows() shouldBe "1"
    }

    @Test
    fun `INV-AC-28460 two events append two rows in order`() {
        importAll(listOf("DOC-1", "DOC-2"), commit = true)
        rows().map { json.readTree(it.data).get("documentNumber").asString() } shouldBe listOf("DOC-1", "DOC-2")
    }

    @Test
    fun `INV-AC-28460 a rolled back transaction leaves no outbox row and no prior_evidence row`() {
        importAll(listOf("DOC-1"), commit = false)
        rows() shouldBe emptyList()
        priorRows() shouldBe "0"
    }
}
