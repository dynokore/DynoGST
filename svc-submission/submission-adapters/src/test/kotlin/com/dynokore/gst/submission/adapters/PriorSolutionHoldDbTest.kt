package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqPriorSolutionHoldStore
import com.dynokore.gst.submission.application.migration.RaisePriorSolutionHold
import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision
import com.dynokore.platform.tenancykernel.TenantId
import gst.build.konsist.MigrationLint
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import java.io.File
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The SUB-017 hold store against the real database: key, tenant isolation, grants, open state (INV-T-28448). */
class PriorSolutionHoldDbTest {
    private val created = mutableListOf<UUID>()
    private val connections = mutableListOf<Connection>()
    private val clock = Clock.fixed(Instant.parse("2026-11-25T01:00:00Z"), ZoneOffset.UTC)
    private val sub017 =
        PriorSolutionDecision.HoldForCorrection("SUB-017", HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT)

    @AfterEach
    fun cleanUp() {
        connections.forEach { it.close() }
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                created.forEach { t ->
                    it.execute("delete from submission.prior_solution_hold_resolution where tenant_id = '$t'")
                    it.execute("delete from submission.prior_solution_hold where tenant_id = '$t'")
                }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private inner class Session(
        val tenant: UUID,
    ) {
        val connection: Connection =
            SubmissionDb
                .connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD)
                .also {
                    it.autoCommit = false
                    connections += it
                }
        val store = JooqPriorSolutionHoldStore(DSL.using(connection, SQLDialect.POSTGRES))
        val raise = RaisePriorSolutionHold(store, clock)
        val id = TenantId(tenant)

        fun <T> tx(block: () -> T): T =
            try {
                SignedBindings.bind(connection, tenant)
                block().also { connection.commit() }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }

        fun sql(statement: String) = tx { connection.createStatement().use { it.executeUpdate(statement) } }
    }

    private fun sqlState(e: Throwable) =
        generateSequence(e) { it.cause }.filterIsInstance<PSQLException>().first().sqlState

    private fun count(sql: String): Int =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun resolve(
        s: Session,
        doc: String,
        version: Int,
    ) = s.sql(
        "insert into submission.prior_solution_hold_resolution values " +
            "('${s.tenant}', '$doc', $version, 'ok', 'ev-1', 'U1', now())",
    )

    @Test
    fun `INV-AC-28447 one row per version, open until a resolution row exists, isolated per tenant`() {
        val s = Session(tenant())
        val other = Session(tenant())
        val first = s.tx { s.raise.execute(s.id, "D", 1, "E1", sub017) }
        s.tx { s.raise.execute(s.id, "D", 1, "E2", sub017) } shouldBe first
        first.reason shouldBe HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT
        first.entityRef shouldBe "E1"
        first.raisedAt shouldBe clock.instant()
        count("select count(*) from submission.prior_solution_hold where tenant_id = '${s.tenant}'") shouldBe 1
        other.tx { other.raise.execute(other.id, "D", 1, "E1", sub017) }
        s.tx { s.store.open(s.id, "D", 1) } shouldBe first
        s.tx { s.store.open(s.id, "D", 2) } shouldBe null
        // another tenant's key read through this tenant's binding: the filter and the policy both hide it
        s.tx { s.store.open(other.id, "D", 1) } shouldBe null
        resolve(s, "D", 1)
        s.tx { s.store.open(s.id, "D", 1) } shouldBe null
        other.tx { other.store.open(other.id, "D", 1) }?.docUuid shouldBe "D"
    }

    @Test
    fun `INV-AC-28447 look-alikes version 2 and the same doc in another tenant are separate rows`() {
        val s = Session(tenant())
        val other = Session(tenant())
        s.tx { s.raise.execute(s.id, "D", 1, "E1", sub017) }
        s.tx { s.raise.execute(s.id, "D", 2, "E1", sub017) }
        other.tx { other.raise.execute(other.id, "D", 1, "E1", sub017) }
        resolve(s, "D", 1)
        s.tx { s.store.open(s.id, "D", 2)?.version } shouldBe 2
        other.tx { other.store.open(other.id, "D", 1)?.version } shouldBe 1
        // one resolution per hold, and none without a hold
        assertThrows<Exception> { resolve(s, "D", 1) }
        assertThrows<Exception> { resolve(s, "D", 9) }
    }

    @Test
    fun `INV-AC-28447 an update or delete of the hold table is refused by the database and code SUB-016 is refused`() {
        val s = Session(tenant())
        s.tx { s.raise.execute(s.id, "D", 1, "E1", sub017) }
        listOf(
            "update submission.prior_solution_hold set entity_ref = 'X'",
            "delete from submission.prior_solution_hold",
            "update submission.prior_solution_hold_resolution set reason = 'X'",
            "delete from submission.prior_solution_hold_resolution",
        ).forEach { statement ->
            val failure = assertThrows<Exception> { s.sql(statement) }
            sqlState(failure) shouldBe "42501"
        }
        val bad =
            assertThrows<Exception> {
                s.sql(
                    "insert into submission.prior_solution_hold values " +
                        "('${s.tenant}', 'D', 5, 'E1', 'NO_PREDECESSOR_ESTABLISHED', 'SUB-016', now())",
                )
            }
        sqlState(bad) shouldBe "23514"
        count("select count(*) from submission.prior_solution_hold where tenant_id = '${s.tenant}'") shouldBe 1
    }

    @Test
    fun `INV-AC-28447 the migration passes the lint and starts with the lock timeout`() {
        val sql = File("src/main/resources/db/migration/V202611251150__prior_solution_hold.sql").readText()
        MigrationLint.lint("V202611251150__prior_solution_hold.sql", listOf(1 to sql)) shouldBe emptyList()
        sql.startsWith("SET lock_timeout = '5s';") shouldBe true
    }
}
