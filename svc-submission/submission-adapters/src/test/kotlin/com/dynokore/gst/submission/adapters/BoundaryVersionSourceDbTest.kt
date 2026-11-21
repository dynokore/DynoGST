package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqBoundaryVersionSource
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.boundary.BoundaryVersion
import com.dynokore.gst.submission.application.boundary.BoundaryVersionSource
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** The boundary version reader against the test database as `submission_runtime` (INV-REQ-1005). */
class BoundaryVersionSourceDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val seedDsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val statements = mutableListOf<String>()
    private val readDsl =
        DSL.using(
            DefaultConfiguration()
                .set(connection)
                .set(SQLDialect.POSTGRES)
                .set(
                    object : ExecuteListener {
                        override fun executeStart(ctx: ExecuteContext) {
                            statements += ctx.sql().orEmpty()
                        }
                    },
                ),
        )
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
    private val source: BoundaryVersionSource = JooqBoundaryVersionSource(readDsl, runner)
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
        seedDsl
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
        // the tenant is in the WHERE clause as well as the row level security (CS-19)
        statements.all { it.lowercase().contains("tenant_id") } shouldBe true
    }

    @Test
    fun `INV-AC-26429 every LifecycleState value round-trips`() {
        val docs = LifecycleState.entries.associateWith { UUID.randomUUID() }
        docs.forEach { (state, doc) -> seed(tenantA, doc, 1, state.name, "E-${state.name}") }
        read(tenantA, docs.values.toList()) shouldContainExactlyInAnyOrder
            docs.map { (state, doc) -> BoundaryVersion(doc, 1, "E-${state.name}", state) }
    }

    @Test
    fun `INV-AC-26429 an empty argument returns an empty list and issues no statement`() {
        read(tenantA, emptyList()) shouldBe emptyList()
        statements shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26429 the reader locks nothing and writes nothing`() {
        val doc = UUID.randomUUID()
        seed(tenantA, doc, 1, "SENT", "E1")
        statements.clear()
        read(tenantA, listOf(doc, UUID.randomUUID()))
        statements.size shouldBe 1
        statements.forEach { sql ->
            val s = sql.lowercase()
            listOf("for update", "insert", "update", "delete").forEach { (s.contains(it)) shouldBe false }
        }
    }
}
