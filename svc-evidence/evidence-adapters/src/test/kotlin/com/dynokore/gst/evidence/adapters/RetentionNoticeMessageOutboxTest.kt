package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.events.RetentionNoticeMessageOutbox
import com.dynokore.gst.evidence.application.retention.RetentionNotice
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-26996: the `MessageRequested` outbox row of a retention notice, its envelope facts and its payload. */
class RetentionNoticeMessageOutboxTest {
    private val tenant = UUID.randomUUID()
    private val json = JsonMapper.builder().build()
    private val type = RetentionNoticeMessageOutbox.EVENT_TYPE
    private val clock = Clock.fixed(Instant.parse("2033-03-31T10:00:00Z"), ZoneOffset.UTC)
    private val partition = "rp_" + "0".repeat(32)

    private val parameters =
        mapOf(
            "periodStart" to "2019-01-01",
            "periodEnd" to "2019-03-31",
            "noticeWindowEndsAt" to "2033-06-29T10:00:00Z",
        )

    private fun notice(
        recipients: List<String> = listOf("holder@example.test"),
        templateParameters: Map<String, String> = parameters,
    ) = RetentionNotice(partition, clock.instant(), 90, recipients, "retention-notice", "EMAIL", templateParameters)

    private fun append(
        notice: RetentionNotice,
        commit: Boolean = true,
        entity: EntityRef = EntityRef(ENTITY),
    ) = EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { connection ->
        connection.autoCommit = false
        SignedBindings.bind(connection, tenant)
        val writer =
            JdbcOutboxWriter(
                DSL.using(connection, SQLDialect.POSTGRES),
                EvidenceDb.SCHEMA,
                "evidence-svc",
                "instance-1",
                EventCatalogue(
                    listOf(CatalogueEntry(type, "gst.access.message-requested.v1", setOf("evidence-svc"))),
                ),
                clock,
            )
        RetentionNoticeMessageOutbox(writer).append(TenantId(tenant), notice, entity)
        if (commit) connection.commit() else connection.rollback()
    }

    private fun rows(): List<List<String>> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select type, data::text, entity_ref, tenant_id::text, subject, correlation_id, causation_id " +
                        "from ${EvidenceDb.SCHEMA}.outbox where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) (1..COLUMNS).map(rs::getString) else null }.toList()
                    }
                }
        }

    /** The access schema of the pinned bundle, as evidence-contract unpacks it; a missing file fails the test. */
    private fun schemaErrors(data: JsonNode): List<String> {
        val file =
            File(
                "../evidence-contract/build/contracts-repo/.bundle/access/schemas/" +
                    "message-requested-payload.schema.json",
            )
        check(file.isFile) { "schema file does not exist: $file" }
        val schema = json.readTree(file.readText())
        // the permissive schema would accept anything under an unknown member, so the member must be declared
        check(schema.get("properties").has("templateParameters")) { "the pinned bundle has no templateParameters" }
        return SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(file.readText())
            .validate(data)
            .map { it.toString() }
    }

    @Test
    fun `INV-AC-26996 the type constant is the shared MessageRequested type`() {
        type shouldBe "com.dynokore.gst.access.MessageRequested.v1"
    }

    @Test
    fun `INV-AC-26996 one row carries the entity, and the partition as subject, correlation and causation`() {
        append(notice())
        val row = rows().single()
        row[0] shouldBe type
        row[2] shouldBe ENTITY
        row[3] shouldBe tenant.toString()
        row[4] shouldBe partition
        row[5] shouldBe "retention-notice:$partition"
        row[6] shouldBe partition
    }

    @Test
    fun `INV-AC-26996 the tenant-wide entity is refused and leaves no outbox row`() {
        shouldThrow<EmissionNotPermitted> { append(notice(), entity = EntityRef.TENANT_WIDE) }
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 the payload is exactly the four members and validates against the pinned schema`() {
        append(notice())
        val payload = json.readTree(rows().single()[1])
        // exact equality of the whole document: no holder name, mobile, content, passcode or other member fits
        payload shouldBe
            json.readTree(
                """{"template": "retention-notice", "recipients": ["holder@example.test"], "channel": "EMAIL",
                   "templateParameters": {"periodStart": "2019-01-01", "periodEnd": "2019-03-31",
                   "noticeWindowEndsAt": "2033-06-29T10:00:00Z"}}""",
            )
        schemaErrors(payload) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 the same payload with a numeric template parameter does not validate`() {
        append(notice())
        val payload = json.readTree(rows().single()[1]) as ObjectNode
        (payload.get("templateParameters") as ObjectNode).put("periodStart", 2019)
        schemaErrors(payload).isNotEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-26996 a rolled back transaction leaves no outbox row`() {
        append(notice(), commit = false)
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a notice without exactly one address writes nothing`() {
        listOf(emptyList(), listOf(" "), listOf("a@example.test", "b@example.test")).forEach {
            shouldThrow<IllegalArgumentException> { append(notice(recipients = it)) }
        }
        rows() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26996 a missing or extra display value writes nothing`() {
        listOf(parameters - "periodEnd", parameters + ("custodyHolderName" to "A Holder"), emptyMap()).forEach {
            shouldThrow<IllegalArgumentException> { append(notice(templateParameters = it)) }
        }
        rows() shouldBe emptyList()
    }

    private companion object {
        const val COLUMNS = 7
        const val ENTITY = "entity-of-the-partition"
    }
}
