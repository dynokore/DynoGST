package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqIntakeListingReader
import com.dynokore.gst.aggregation.application.IntakeListingFilter
import com.dynokore.gst.aggregation.application.IntakeListingPage
import com.dynokore.gst.aggregation.application.IntakeListingPosition
import com.dynokore.gst.aggregation.application.ListSubmittedIntake
import com.dynokore.gst.aggregation.application.ListedIntake
import com.dynokore.gst.aggregation.application.ListedIntakeOperation
import com.dynokore.gst.aggregation.application.ListedIntakeOperation.BATCH
import com.dynokore.gst.aggregation.application.ListedIntakeOperation.TRANSACTIONS
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-28765: the reader returns exactly the permitted intake receipts of a principal in a window. The reads run
 * over the real database as the runtime role. Aggregate ids come from the real intake and closing paths; where a case
 * needs a chosen sort key or recording instant, the superuser writes the row.
 */
class IntakeListingDbTest {
    private val tenant = UUID.randomUUID()
    private val from = Instant.parse("2027-04-01T00:00:00Z")
    private val to = Instant.parse("2027-04-02T00:00:00Z")

    private fun filter(
        principal: String = ALICE,
        recordedFrom: Instant = from,
        recordedTo: Instant = to,
    ) = IntakeListingFilter(principal, recordedFrom, recordedTo)

    /** The use case over the reader on a runtime-role connection (or, with [superuser], one that bypasses RLS). */
    private fun <T> listing(
        bound: UUID = tenant,
        superuser: Boolean = false,
        block: (ListSubmittedIntake) -> T,
    ): T {
        val open =
            if (superuser) {
                AggregationDb.superuser()
            } else {
                AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD)
            }
        return open.use { connection ->
            connection.autoCommit = false
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            val runner = runnerOf(connection, bound, superuser)
            block(ListSubmittedIntake(JooqIntakeListingReader(dsl, runner, tenantsOf(bound))))
        }
    }

    private fun runnerOf(
        connection: Connection,
        bound: UUID,
        superuser: Boolean,
    ) = object : TransactionRunner {
        override fun <R> inTransaction(block: () -> R): R {
            if (!superuser) SignedBindings.bind(connection, bound)
            return block().also { connection.commit() }
        }
    }

    private fun tenantsOf(bound: UUID) =
        object : TenantContext {
            override fun current() = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <R> bind(
                tenant: TenantId,
                actor: String,
                block: () -> R,
            ): R = block()
        }

    /** Every item of [permitted], read page by page with [limit]; each page but the last is full. */
    private fun ListSubmittedIntake.pages(
        filter: IntakeListingFilter,
        permitted: Set<String>,
        limit: Int,
    ): List<List<ListedIntake>> {
        val pages = mutableListOf<List<ListedIntake>>()
        var after: IntakeListingPosition? = null
        do {
            val page = page(filter, permitted, after, limit)
            pages += page.items
            after = page.next
            check(pages.size <= MAX_PAGES) { "the paging never ends" }
        } while (after != null)
        return pages
    }

    @Suppress("LongParameterList")
    private fun seed(
        key: String,
        entity: String = ENTITY,
        principal: String = ALICE,
        at: Instant = from,
        revision: Long = 1,
        operation: ListedIntakeOperation = BATCH,
        owner: UUID = tenant,
    ) = databaseExecute(
        "insert into aggregation.intake_receipt (tenant_id, intake_operation, entity_ref, source_key, " +
            "source_revision, aggregate_type, submitted_by, recorded_at) values ('$owner', '$operation', " +
            "'$entity', '$key', $revision, 'POS', '$principal', '$at')",
    )

    private fun IntakeListingPage.keys() = items.map { it.sourceKey }

    private fun recordAt(
        at: Instant,
        owner: UUID = tenant,
    ) = databaseExecute("update aggregation.intake_receipt set recorded_at = '$at' where tenant_id = '$owner'")

    /** The id of the one aggregate of the tenant that [where] matches. */
    private fun aggregateOf(
        where: String,
        owner: UUID = tenant,
    ): UUID =
        databaseRows("select aggregate_id::text from aggregation.aggregate where tenant_id = '$owner' and $where")
            .single()
            .let(UUID::fromString)

    /** The listed ids of every receipt of [principal], by operation, source key and revision. */
    private fun idsOf(
        principal: String = ALICE,
        owner: UUID = tenant,
    ): Map<String, List<UUID>> =
        listing(owner) { list ->
            val all = filter(principal, Instant.EPOCH, FAR)
            list.page(all, setOf(ENTITY), null, ALL).items.associate {
                "${it.intakeOperation}|${it.sourceKey}|${it.sourceRevision}" to it.aggregateIds
            }
        }

    @Test
    fun `INV-AC-28765 only the principal's receipts match, never those of a look-alike principalRef`() {
        seed("K-OWN")
        listOf("${ALICE}1", ALICE.dropLast(1), ALICE.lowercase(), "$ALICE ", BOB).forEachIndexed { index, other ->
            seed("K-OTHER-$index", entity = "E-OTHER-$index", principal = other)
        }

        listing { list ->
            list.entityRefs(filter()) shouldBe setOf(ENTITY)
            val everyEntity = setOf(ENTITY) + (0..4).map { "E-OTHER-$it" }
            list.page(filter(), everyEntity, null, ALL).keys() shouldBe listOf("K-OWN")
            // a principalRef is compared whole: no pattern matches
            list.entityRefs(filter("PRN-%")).shouldBeEmpty()
            list.page(filter("PRN-0008_"), everyEntity, null, ALL).items.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-28765 recordedFrom is included and recordedTo excluded, to the microsecond`() {
        seed("K-1-BEFORE", entity = "E-BEFORE", at = from.minusNanos(MICRO))
        seed("K-2-FROM", entity = "E-FROM", at = from)
        seed("K-3-LAST", entity = "E-LAST", at = to.minusNanos(MICRO))
        seed("K-4-TO", entity = "E-TO", at = to)
        val every = setOf("E-BEFORE", "E-FROM", "E-LAST", "E-TO")

        listing { list ->
            list.entityRefs(filter()) shouldBe setOf("E-FROM", "E-LAST")
            val page = list.page(filter(), every, null, ALL)
            page.keys() shouldBe listOf("K-2-FROM", "K-3-LAST")
            page.items.map { it.recordedAt } shouldBe listOf(from, to.minusNanos(MICRO))

            // bounds finer than a microsecond: a stored instant is compared with the exact bound
            val later = filter(recordedFrom = from.plusNanos(1), recordedTo = to.plusNanos(1))
            list.entityRefs(later) shouldBe setOf("E-LAST", "E-TO")
            list.page(later, every, null, ALL).keys() shouldBe listOf("K-3-LAST", "K-4-TO")
            val earlier = filter(recordedFrom = from.minusNanos(1), recordedTo = to.minusNanos(1))
            list.entityRefs(earlier) shouldBe setOf("E-FROM", "E-LAST")
            list.page(earlier, every, null, ALL).keys() shouldBe listOf("K-2-FROM", "K-3-LAST")
        }
    }

    @Test
    fun `INV-AC-28765 a second tenant's receipts and aggregates are excluded, with and without row security`() {
        val other = UUID.randomUUID()
        // both tenants accept the same triple through both operations, so only the tenant tells their rows apart
        listOf(tenant, other).forEach { owner ->
            receiptWorld(owner) { w ->
                w.batch(1) shouldBe 202
                w.dated(1, "T-1") shouldBe 202
                transmit(owner)
                w.batch(2) shouldBe 202
                w.principal = "svc:aggregation-svc"
                w.closeWindows(LocalDate.of(2027, 3, 15))
            }
            recordAt(from, owner)
        }
        seed("K-FOREIGN", entity = "E-FOREIGN", owner = other)

        listOf(false, true).forEach { superuser ->
            listing(superuser = superuser) { list ->
                list.entityRefs(filter()) shouldBe setOf(ENTITY)
                val items = list.page(filter(), setOf(ENTITY, "E-FOREIGN"), null, ALL).items
                items.map { "${it.intakeOperation}|${it.sourceRevision}" } shouldBe
                    listOf("BATCH|1", "TRANSACTIONS|1", "BATCH|2")
                val own =
                    databaseRows("select aggregate_id::text from aggregation.aggregate where tenant_id = '$tenant'")
                        .map(UUID::fromString)
                items.forEach {
                    it.aggregateIds shouldHaveSize 1
                    (it.aggregateIds.single() in own) shouldBe true
                }
            }
        }
    }

    @Test
    fun `INV-AC-28765 the entity filter is applied before the limit over interleaved entities`() {
        listOf("A", "B", "A", "B", "A", "B", "A", "B", "A").forEachIndexed { index, entity ->
            seed("K-${index + 1}", entity = "E-$entity")
        }

        listing { list ->
            list.entityRefs(filter()) shouldBe setOf("E-A", "E-B")
            list.pages(filter(), setOf("E-A"), 2).map { page -> page.map { it.sourceKey } } shouldBe
                listOf(listOf("K-1", "K-3"), listOf("K-5", "K-7"), listOf("K-9"))
            list.pages(filter(), setOf("E-B"), 2).map { page -> page.map { it.sourceKey } } shouldBe
                listOf(listOf("K-2", "K-4"), listOf("K-6", "K-8"))
            // a full last page has no next position
            list.page(filter(), setOf("E-B"), null, 4).next shouldBe null
            // look-alikes of a permitted entityRef permit nothing
            list.page(filter(), setOf("E-A1", "E-", "e-a", "E-%"), null, ALL).items.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-28765 no permitted entity gives the empty page without a statement`() {
        seed("K-1")
        val untouched =
            object : TransactionRunner {
                override fun <R> inTransaction(block: () -> R): R = error("no transaction may be opened")
            }
        // a DSL without a connection fails on any statement it is asked to run
        val reader = JooqIntakeListingReader(DSL.using(SQLDialect.POSTGRES), untouched, tenantsOf(tenant))

        ListSubmittedIntake(reader).page(filter(), emptySet(), null, ALL) shouldBe IntakeListingPage(emptyList(), null)
    }

    @Test
    fun `INV-AC-28765 the order is sourceKey, recordedAt, sourceRevision, entityRef and operation, over pages`() {
        val later = from.plusSeconds(1)
        // listed in the expected order; each neighbour pair differs first in the next part of the key, and every
        // later part runs against it
        val expected =
            listOf(
                Row("K-1", later, 9, "E-B", TRANSACTIONS),
                Row("K-2", from, 3, "E-B", TRANSACTIONS),
                Row("K-2", later, 1, "E-B", TRANSACTIONS),
                Row("K-2", later, 2, "E-A", TRANSACTIONS),
                Row("K-2", later, 2, "E-B", BATCH),
                Row("K-2", later, 2, "E-B", TRANSACTIONS),
                // a revision is ordered as a number, not as text
                Row("K-3", from, 9, "E-A", BATCH),
                Row("K-3", from, 10, "E-A", BATCH),
            )
        expected.reversed().forEach {
            seed(it.key, entity = it.entity, at = it.at, revision = it.revision, operation = it.operation)
        }

        listing { list ->
            listOf(1, 2, 3, ALL).forEach { limit ->
                list
                    .pages(filter(), setOf("E-A", "E-B"), limit)
                    .flatten()
                    .map { Row(it.sourceKey, it.recordedAt, it.sourceRevision, it.entityRef, it.intakeOperation) }
                    .shouldBe(expected)
            }
            list.page(filter(), setOf("E-A", "E-B"), null, 1).next shouldBe
                IntakeListingPosition("K-1", later, 9, "E-B", TRANSACTIONS)
            list.page(filter(), setOf("E-A", "E-B"), null, 1).items.single() shouldBe
                ListedIntake(TRANSACTIONS, "E-B", "K-1", 9, AggregateType.POS, later, emptyList())
        }
    }

    private data class Row(
        val key: String,
        val at: Instant,
        val revision: Long,
        val entity: String,
        val operation: ListedIntakeOperation,
    )

    @Test
    fun `INV-AC-28765 a batch item lists its own aggregate, or the one its unchanged revision was compared with`() {
        receiptWorld(tenant) { w ->
            // revision 2 replaces revision 1; revision 3 supplements the transmitted revision 2
            w.batch(1) shouldBe 202
            w.batch(2, "120.00") shouldBe 202
            transmit(tenant)
            w.batch(3, "150.00") shouldBe 202
            // zero difference over the supplementary supersedes it; revision 5 is zero over the transmitted one
            w.batch(4, "120.00") shouldBe 202
            w.batch(5, "120.00") shouldBe 202
        }
        val first = aggregateOf("source_revision = 1")
        val second = aggregateOf("source_revision = 2")
        val third = aggregateOf("source_revision = 3")

        idsOf() shouldBe
            mapOf(
                "BATCH|$KEY|1" to listOf(first),
                "BATCH|$KEY|2" to listOf(second),
                "BATCH|$KEY|3" to listOf(third),
                "BATCH|$KEY|4" to listOf(second),
                "BATCH|$KEY|5" to listOf(second),
            )
    }

    @Test
    fun `INV-AC-28765 a batch item lists each id of its aggregate and its seen revision once`() {
        receiptWorld(tenant) { w ->
            w.batch(1) shouldBe 202
            w.batch(1, key = "SK-OTHER") shouldBe 202
        }
        val own = aggregateOf("source_key = '$KEY'")
        val compared = aggregateOf("source_key = 'SK-OTHER'")
        val seen =
            "insert into aggregation.seen_revision (tenant_id, entity_ref, source_key, source_revision, " +
                "aggregate_id) values ('$tenant', '$ENTITY'"

        databaseExecute("$seen, '$KEY', 1, '$compared')")
        databaseExecute("$seen, 'SK-OTHER', 1, '$compared')")

        idsOf() shouldBe
            mapOf("BATCH|$KEY|1" to listOf(own, compared).sorted(), "BATCH|SK-OTHER|1" to listOf(compared))
    }

    @Test
    fun `INV-AC-28765 a dated-transaction item lists no id while its window is open, then the ids of its rows`() {
        receiptWorld(tenant) { w ->
            w.dated(1, "T-1", "T-2", "T-3") shouldBe 202
            idsOf() shouldBe mapOf("TRANSACTIONS|$KEY|1" to emptyList())

            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 15))
        }
        val window = aggregateOf("source_key like 'window:%'")
        // three rows in one aggregate: the id is listed once
        idsOf() shouldBe mapOf("TRANSACTIONS|$KEY|1" to listOf(window))

        receiptWorld(tenant) { w ->
            transmit(tenant)
            w.dated(1, "T-LATE", key = "SK-LATE") shouldBe 202
            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 16))
        }
        val late = aggregateOf("source_key like 'window-late:%'")
        late shouldNotBe window
        idsOf() shouldBe mapOf("TRANSACTIONS|$KEY|1" to listOf(window), "TRANSACTIONS|SK-LATE|1" to listOf(late))

        // rows of one triple in two aggregates and one row in none: both ids, and nothing for the third
        val move = "update aggregation.aggregate_transaction set aggregate_id ="
        val row = "where tenant_id = '$tenant' and source_key = '$KEY' and transaction_ref ="
        databaseExecute("$move '$late' $row 'T-2'")
        databaseExecute("$move null $row 'T-3'")
        idsOf()["TRANSACTIONS|$KEY|1"] shouldBe listOf(window, late).sorted()
    }

    @Test
    fun `INV-AC-28765 two principals with one triple each see their own item with that operation's ids only`() {
        receiptWorld(tenant) { w ->
            w.batch(1) shouldBe 202
            w.principal = BOB
            w.dated(1, "T-1") shouldBe 202
        }
        val batch = aggregateOf("source_key = '$KEY'")
        // the window is open: the transaction item must not borrow the batch aggregate of the same triple
        idsOf(ALICE) shouldBe mapOf("BATCH|$KEY|1" to listOf(batch))
        idsOf(BOB) shouldBe mapOf("TRANSACTIONS|$KEY|1" to emptyList())

        receiptWorld(tenant) { w ->
            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 15))
        }
        val dated =
            databaseRows(
                "select distinct aggregate_id::text from aggregation.aggregate_transaction " +
                    "where tenant_id = '$tenant'",
            ).single().let(UUID::fromString)
        dated shouldNotBe batch
        idsOf(ALICE) shouldBe mapOf("BATCH|$KEY|1" to listOf(batch))
        idsOf(BOB) shouldBe mapOf("TRANSACTIONS|$KEY|1" to listOf(dated))
    }

    @Test
    fun `INV-AC-28765 both operations of one triple at one instant come on two pages of one, each once`() {
        receiptWorld(tenant) { w ->
            w.batch(1) shouldBe 202
            w.dated(1, "T-1") shouldBe 202
            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 15))
        }
        recordAt(from)
        val batch = aggregateOf("source_key = '$KEY'")
        val dated =
            databaseRows(
                "select distinct aggregate_id::text from aggregation.aggregate_transaction " +
                    "where tenant_id = '$tenant'",
            ).single().let(UUID::fromString)
        dated shouldNotBe batch

        listing { list ->
            list.pages(filter(), setOf(ENTITY), 1) shouldBe
                listOf(
                    listOf(ListedIntake(BATCH, ENTITY, KEY, 1, AggregateType.POS, from, listOf(batch))),
                    listOf(ListedIntake(TRANSACTIONS, ENTITY, KEY, 1, AggregateType.STI, from, listOf(dated))),
                )
        }
    }

    private companion object {
        const val ALL = 100
        const val MAX_PAGES = 20
        const val MICRO = 1_000L
        val FAR: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
