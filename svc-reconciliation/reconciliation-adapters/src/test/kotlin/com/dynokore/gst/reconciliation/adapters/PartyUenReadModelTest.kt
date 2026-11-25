package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReadModel
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-28552 (INV-T-28553): the run read model stores the seller and buyer UEN only from an SG_UEN party identifier
 * and null otherwise, through the real handler and the real store.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PartyUenReadModelTest {
    private val tenant = UUID.randomUUID()

    private fun party(
        scheme: String?,
        value: String?,
        name: String = "Acme Pte Ltd",
        gstn: String = "M12345678X",
    ): String {
        val s = scheme?.let { ""","identifierScheme":"$it"""" }.orEmpty()
        val v = value?.let { ""","identifierValue":"$it"""" }.orEmpty()
        return """{"name":"$name","gstn":"$gstn"$s$v}"""
    }

    private fun payload(
        doc: String,
        supplier: String?,
        buyer: String?,
    ): String {
        val inner =
            listOfNotNull(supplier?.let { """"supplier":$it""" }, buyer?.let { """"buyer":$it""" }).joinToString(",")
        return """{"docUuid":"$doc","version":1,"transactionDate":"2026-02-10","prescribedPeriodRef":"2026-Q1",""" +
            """"scopeVerdict":"SUBMITTABLE","docsequence":10,"canonicalDocument":{""" +
            """"currency":{"value":"SGD","classification":"NONE"},""" +
            """"lines":{"value":[],"classification":"NONE"},""" +
            """"parties":{"value":{$inner},"classification":"NONE"}}}"""
    }

    private fun envelope(
        doc: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical-svc",
        type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
        subject = doc,
        time = Instant.parse("2026-11-18T01:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E1"),
        docUuid = DocUuid(UUID.fromString(doc)),
        transactionDate = LocalDate.of(2026, 2, 10),
        docSequence = 10,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    /** Applies the event and returns the stored (seller_uen, buyer_uen). */
    private fun store(
        supplier: String?,
        buyer: String?,
    ): Pair<String?, String?> {
        val doc = UUID.randomUUID().toString()
        return ReconciliationDb.connect(ReconciliationDb.RUNTIME, ReconciliationDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            CanonicalVersionCreatedHandler(JooqRunReadModel(DSL.using(c, SQLDialect.POSTGRES)))
                .handle(envelope(doc, payload(doc, supplier, buyer)))
            c
                .prepareStatement(
                    "select seller_uen, buyer_uen from reconciliation.document_version where doc_uuid = ?::uuid",
                ).use { ps ->
                    ps.setString(1, doc)
                    ps.executeQuery().use { rs ->
                        check(rs.next())
                        (rs.getString(1) to rs.getString(2)).also { c.commit() }
                    }
                }
        }
    }

    @Test
    fun `INV-AC-28552 both UENs are stored from SG_UEN identifiers`() {
        store(party("SG_UEN", "201912345K"), party("SG_UEN", "T08XX1234A")) shouldBe ("201912345K" to "T08XX1234A")
    }

    @Test
    fun `INV-AC-28552 scheme UEN is stored as null`() {
        store(party("UEN", "201912345K"), party("UEN", "T08XX1234A")) shouldBe (null to null)
    }

    @Test
    fun `INV-AC-28552 lower case sg_uen is stored as null`() {
        store(party("sg_uen", "201912345K"), party("sg_uen", "T08XX1234A")) shouldBe (null to null)
    }

    @Test
    fun `INV-AC-28552 scheme with a trailing space is stored as null`() {
        store(party("SG_UEN ", "201912345K"), party("SG_UEN ", "T08XX1234A")) shouldBe (null to null)
    }

    @Test
    fun `INV-AC-28552 blank value is stored as null`() {
        store(party("SG_UEN", "  "), party("SG_UEN", "")) shouldBe (null to null)
    }

    @Test
    fun `INV-AC-28552 absent party is stored as null`() {
        store(null, party("SG_UEN", "T08XX1234A")) shouldBe (null to "T08XX1234A")
        store(party("SG_UEN", "201912345K"), null) shouldBe ("201912345K" to null)
    }

    @Test
    fun `INV-AC-28552 party without scheme never takes the UEN from name or gstn`() {
        store(party(null, null, name = "201912345K"), party(null, "T08XX1234A")) shouldBe (null to null)
    }
}
