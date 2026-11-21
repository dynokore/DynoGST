package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPayloadReadyDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqBoundaryVersionSource
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.boundary.BoundaryVersion
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The boundary version reader against the test database as `submission_runtime` (INV-REQ-1005). */
class BoundaryVersionSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                SignedBindings.bind(connection, checkNotNull(bound.get()))
                return try {
                    block().also { connection.commit() }
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                }
            }
        }
    private val source: BoundaryVersionSource = JooqBoundaryVersionSource(dsl, runner)
    private val sub = Submission.SUBMISSION_
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        bound.set(tenant)
        try {
            return runner.inTransaction(block)
        } finally {
            bound.remove()
        }
    }

    private fun seed(
        tenant: UUID,
        doc: UUID,
        version: Int,
        state: String,
        entity: String,
    ) = asTenant(tenant) {
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc.toString())
            .set(sub.VERSION, version)
            .set(sub.STATE, state)
            .set(sub.ENTITY_REF, entity)
            .execute()
    }

    private fun read(
        tenant: UUID,
        docs: Collection<UUID>,
    ) = asTenant(tenant) { source.versions(TenantId(tenant), docs) }

    @Test
    fun `INV-AC-26429 returns every version row of the listed Doc UUIDs of the bound tenant`() {
        val two = UUID.randomUUID()
        val one = UUID.randomUUID()
        val unlisted = UUID.randomUUID()
        seed(tenantA, two, 1, "SUPERSEDED", "E1")
        seed(tenantA, two, 2, "ACKNOWLEDGED", "E1")
        seed(tenantA, one, 1, "QUEUED", "E2")
        seed(tenantA, unlisted, 1, "SENT", "E3")
        read(tenantA, listOf(two, one)) shouldContainExactlyInAnyOrder
            listOf(
                BoundaryVersion(two, 1, "E1", LifecycleState.SUPERSEDED),
                BoundaryVersion(two, 2, "E1", LifecycleState.ACKNOWLEDGED),
                BoundaryVersion(one, 1, "E2", LifecycleState.QUEUED),
            )
    }

    @Test
    fun `INV-AC-26429 a Doc UUID with no row gives nothing`() {
        val present = UUID.randomUUID()
        seed(tenantA, present, 1, "SENT", "E1")
        read(tenantA, listOf(UUID.randomUUID())) shouldBe emptyList()
        read(tenantA, listOf(present, UUID.randomUUID())) shouldBe
            listOf(BoundaryVersion(present, 1, "E1", LifecycleState.SENT))
    }

    @Test
    fun `INV-AC-26429 the same Doc UUID under another tenant is not returned`() {
        val doc = UUID.randomUUID()
        seed(tenantA, doc, 1, "SENT", "EA")
        // the primary key is (Doc UUID, version) across tenants, so the other tenant holds version 2
        seed(tenantB, doc, 2, "REJECTED", "EB")
        read(tenantA, listOf(doc)) shouldBe listOf(BoundaryVersion(doc, 1, "EA", LifecycleState.SENT))
        read(tenantB, listOf(doc)) shouldBe listOf(BoundaryVersion(doc, 2, "EB", LifecycleState.REJECTED))
        // This case shows the result under row level security only; the tenant predicate in the WHERE clause (CS-19)
        // is proved by the statement case `the reader locks nothing and writes nothing and carries the tenant ...`.
    }

    @Test
    fun `INV-AC-26429 every LifecycleState value round-trips`() {
        val docs = LifecycleState.entries.associateWith { UUID.randomUUID() }
        docs.forEach { (state, doc) -> seed(tenantA, doc, 1, state.name, "E-${state.name}") }
        read(tenantA, docs.values.toList()) shouldContainExactlyInAnyOrder
            docs.map { (state, doc) -> BoundaryVersion(doc, 1, "E-${state.name}", state) }
    }

    /** The statement text the reader issues, recorded by a mock JDBC connection that answers no row. */
    private fun issued(docs: Collection<UUID>): List<String> {
        val recorded = mutableListOf<String>()
        val mock =
            DSL.using(
                MockConnection { ctx ->
                    recorded.add(ctx.sql())
                    arrayOf(MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(sub.DOC_UUID)))
                },
                SQLDialect.POSTGRES,
            )
        val noTransaction =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        JooqBoundaryVersionSource(mock, noTransaction).versions(TenantId(tenantA), docs)
        return recorded
    }

    @Test
    fun `INV-AC-26429 an empty argument returns an empty list and issues no statement`() {
        read(tenantA, emptyList()) shouldBe emptyList()
        issued(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26429 the reader locks nothing and writes nothing and carries the tenant in the WHERE clause`() {
        val statements = issued(listOf(UUID.randomUUID(), UUID.randomUUID()))
        statements.size shouldBe 1
        val text = statements.single().lowercase().replace("\"", "")
        text.contains("tenant_id = cast(? as uuid)") shouldBe true
        listOf("for update", "insert", "update", "delete").filter { text.contains(it) } shouldBe emptyList()
    }

    private fun payloadReady(docUuid: String) =
        """{"docUuid":"$docUuid","version":1,"submissionType":"SUBMIT","specificationVersion":"1","payloadBytes":"AQ==","payloadSha256":"x"}"""

    @Test
    fun `INV-AC-26429 the PayloadReady reader stores the canonical lower-case Doc UUID and refuses other forms`() {
        val fixed = UUID.randomUUID()
        val reader = JacksonPayloadReadyDataReader()
        reader.read(payloadReady(fixed.toString().uppercase())).docUuid shouldBe fixed.toString()
        listOf("1-1-1-1-1", "not-a-uuid").forEach { bad ->
            shouldThrow<ContractViolation> { reader.read(payloadReady(bad)) }.javaClass shouldBe
                ContractViolation::class.java
        }
    }

    @Test
    fun `INV-AC-26429 a row stored with the reader Doc UUID of upper-case input is found by the boundary reader`() {
        val fixed = UUID.randomUUID()
        val stored = JacksonPayloadReadyDataReader().read(payloadReady(fixed.toString().uppercase())).docUuid
        asTenant(tenantA) {
            JooqSubmissionStore(dsl).insertReady(
                TenantId(tenantA),
                NewReadySubmission(stored, 1, "EU", null, byteArrayOf(1), "x", SubmissionKind.entries.first()),
            )
        }
        read(tenantA, listOf(fixed)) shouldBe listOf(BoundaryVersion(fixed, 1, "EU", LifecycleState.READY))
    }
}
