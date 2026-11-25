package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqCustodyHolderReader
import com.dynokore.gst.evidence.adapters.persistence.JooqNoticeCandidates
import com.dynokore.gst.evidence.application.retention.CustodyHolderReader
import com.dynokore.gst.evidence.application.retention.NoticeCandidate
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-26996 at the database: the candidate reader and the holder reader, real PostgreSQL. */
class RetentionNoticeDbTest {
    private val tenant = UUID.randomUUID()
    private val at = Instant.parse("2033-03-31T10:00:00Z")

    private fun <T> runtime(
        bound: UUID = tenant,
        commit: Boolean = true,
        block: (Connection, DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, bound)
            val result = block(c, DSL.using(c, SQLDialect.POSTGRES))
            if (commit) c.commit() else c.rollback()
            result
        }

    private fun <T> superuser(
        sql: String,
        vararg args: Any,
        read: (java.sql.PreparedStatement) -> T,
    ): T =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                read(st)
            }
        }

    private fun values(
        sql: String,
        vararg args: Any,
    ): List<String> =
        superuser(sql, *args) { st ->
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun partition(
        owner: UUID = tenant,
        period: String = "2019-Q1",
        state: String = "ATTACHED",
    ): String {
        runtime(owner) { c, _ ->
            RetentionPartitionFixture.ensureRetentionPartition(c, "decision_trace", owner, "E1", period)
        }
        val id =
            values(
                "select partition_id from evidence.retention_partition where tenant_id = ? and period_from = ?",
                owner,
                period,
            ).single()
        superuser("update evidence.retention_partition set state = ? where partition_id = ?", state, id) {
            it.executeUpdate()
        }
        return id
    }

    private fun holderRow(
        email: String,
        receivedAt: Instant,
        window: Int? = null,
    ) {
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into evidence.offboarding_custody_holder values (?, ?, 'A Holder', ?, '+6590000000', ?, ?)",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, "OFF-" + UUID.randomUUID())
                    it.setString(3, email)
                    it.setObject(4, window, java.sql.Types.INTEGER)
                    it.setTimestamp(5, Timestamp.from(receivedAt))
                    it.executeUpdate()
                }
        }
    }

    private fun pending(
        bound: UUID = tenant,
        asked: UUID = tenant,
    ): List<NoticeCandidate> = runtime(bound) { _, dsl -> JooqNoticeCandidates(dsl).pending(asked.toString()) }

    private fun holder(
        bound: UUID = tenant,
        asked: UUID = tenant,
    ): CustodyHolderReader.CustodyHolder? =
        runtime(bound) { _, dsl -> JooqCustodyHolderReader(dsl).holder(asked.toString()) }

    @Test
    fun `INV-AC-26996 the candidates are the attached and detached partitions with no notice`() {
        val attached = partition(period = "2019-Q1")
        val detached = partition(period = "2019-Q2", state = "DETACHED")
        partition(period = "2019-Q3", state = "ARCHIVED")
        partition(period = "2019-Q4", state = "DROPPED")
        val noticed = partition(period = "2020-Q1")
        superuser(
            "insert into evidence.retention_notice values (?, ?, now(), 30, now())",
            tenant,
            noticed,
        ) { it.executeUpdate() }
        pending().sortedBy { it.periodFrom } shouldBe
            listOf(
                NoticeCandidate(attached, LocalDate.of(2019, 1, 1), LocalDate.of(2019, 3, 31)),
                NoticeCandidate(detached, LocalDate.of(2019, 4, 1), LocalDate.of(2019, 6, 30)),
            )
    }

    @Test
    fun `INV-AC-26996 a partition whose period cannot be dated stays a candidate`() {
        val id = partition(period = "P1")
        pending() shouldBe listOf(NoticeCandidate(id, LocalDate.MIN, LocalDate.MAX))
    }

    @Test
    fun `INV-AC-26996 another tenant reads no candidate and no holder of this tenant`() {
        partition()
        holderRow("a@example.test", at)
        val other = UUID.randomUUID()
        pending().size shouldBe 1
        holder()?.email shouldBe "a@example.test"
        // bound to another tenant and asking for this one: row level security shows no row
        pending(bound = other) shouldBe emptyList()
        holder(bound = other) shouldBe null
        pending(bound = other, asked = other) shouldBe emptyList()
        holder(bound = other, asked = other) shouldBe null
        // bound to this tenant and asking for another one reads nothing either
        pending(asked = other) shouldBe emptyList()
        holder(asked = other) shouldBe null
    }

    @Test
    fun `INV-AC-26996 the holder is the row received last, with its stored window`() {
        holderRow("old@example.test", at.minusSeconds(60), window = 200)
        holderRow("new@example.test", at, window = 120)
        holderRow("older@example.test", at.minusSeconds(120))
        holder() shouldBe CustodyHolderReader.CustodyHolder("A Holder", "new@example.test", "+6590000000", 120)
    }

    @Test
    fun `INV-AC-26996 two rows with an equal greatest received_at give null, an older tie does not`() {
        holderRow("a@example.test", at)
        holderRow("b@example.test", at)
        holder() shouldBe null
        holderRow("c@example.test", at.plusSeconds(1))
        holder()?.email shouldBe "c@example.test"
    }

    @Test
    fun `INV-AC-26996 no holder row gives null`() {
        holder() shouldBe null
    }
}
