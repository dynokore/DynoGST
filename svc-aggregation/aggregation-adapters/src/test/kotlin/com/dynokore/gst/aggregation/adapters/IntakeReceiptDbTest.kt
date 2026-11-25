package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.contract.model.AggregationBatchRequest
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * INV-AC-28764: aggregation-svc records the submitting principal of every accepted intake. All calls run over the
 * real database as the runtime role; the stored rows are read back by the superuser and compared by equality.
 */
class IntakeReceiptDbTest {
    private val tenant = UUID.randomUUID()

    /** The receipt was written by the transaction that wrote the row of [table] matched by [where], at its time. */
    private fun sameTransaction(
        operation: String,
        revision: Int,
        table: String,
        where: String,
        timeColumn: String? = "created_at",
    ) {
        val time = timeColumn?.let { " and d.$it = r.recorded_at" }.orEmpty()
        databaseRows(
            "select count(*)::text from aggregation.intake_receipt r join aggregation.$table d " +
                "on d.tenant_id = r.tenant_id and d.xmin::text = r.xmin::text$time " +
                "where r.tenant_id = '$tenant' and r.intake_operation = '$operation' " +
                "and r.source_revision = $revision and $where",
        ) shouldBe listOf("1")
    }

    private fun outcomeOf(revision: Int) =
        databaseRows(
            "select concat_ws('|', supplementary, superseded_by is not null) from aggregation.aggregate " +
                "where tenant_id = '$tenant' and source_revision = $revision",
        )

    @Test
    fun `INV-AC-28764 a new, replacing and supplementing batch and an unchanged one get one receipt each`() {
        receiptWorld(tenant) { w ->
            val before = databaseRows("select now()::text").single()
            w.batch(1) shouldBe 202
            receiptsOf(tenant) shouldBe listOf(receipt("BATCH", 1, ALICE))
            sameTransaction("BATCH", 1, "aggregate", "d.source_revision = 1")
            databaseRows(
                "select (recorded_at between '$before' and now())::text from aggregation.intake_receipt " +
                    "where tenant_id = '$tenant'",
            ) shouldBe listOf("true")

            w.principal = BOB
            w.batch(2, "120.00") shouldBe 202
            outcomeOf(1) shouldBe listOf("f|t")
            sameTransaction("BATCH", 2, "aggregate", "d.source_revision = 2")

            transmit(tenant)
            w.principal = ALICE
            w.batch(3, "150.00") shouldBe 202
            outcomeOf(3) shouldBe listOf("t|f")
            sameTransaction("BATCH", 3, "aggregate", "d.source_revision = 3")

            // zero difference over the supplementary: it is superseded and revision 4 is only recorded as seen
            w.principal = BOB
            w.batch(4, "120.00") shouldBe 202
            outcomeOf(3) shouldBe listOf("t|t")
            outcomeOf(4) shouldBe emptyList()
            sameTransaction("BATCH", 4, "seen_revision", "d.source_revision = 4", timeColumn = null)

            // zero difference over the transmitted aggregate: only the seen revision is stored
            w.principal = ALICE
            w.batch(5, "120.00") shouldBe 202
            outcomeOf(5) shouldBe emptyList()
            sameTransaction("BATCH", 5, "seen_revision", "d.source_revision = 5", timeColumn = null)

            receiptsOf(tenant) shouldBe
                listOf(
                    receipt("BATCH", 1, ALICE),
                    receipt("BATCH", 2, BOB),
                    receipt("BATCH", 3, ALICE),
                    receipt("BATCH", 4, BOB),
                    receipt("BATCH", 5, ALICE),
                )
        }
    }

    @Test
    fun `INV-AC-28764 an accepted transaction intake gets one receipt, whatever the number of its rows`() {
        receiptWorld(tenant) { w ->
            w.dated(1, "T-1", "T-2", "T-3") shouldBe 202
            w.principal = BOB
            w.dated(2, "T-1") shouldBe 202

            receiptsOf(tenant) shouldBe listOf(receipt("TRANSACTIONS", 1, ALICE), receipt("TRANSACTIONS", 2, BOB))
            databaseRows(
                "select count(*)::text from aggregation.intake_receipt r join aggregation.aggregate_transaction t " +
                    "on t.tenant_id = r.tenant_id and t.source_revision = r.source_revision " +
                    "and t.xmin::text = r.xmin::text and t.created_at = r.recorded_at " +
                    "where r.tenant_id = '$tenant' group by r.source_revision order by r.source_revision",
            ) shouldBe listOf("3", "1")
        }
    }

    @Test
    fun `INV-AC-28764 two principals sending one triple through both operations leave two receipts, one each`() {
        receiptWorld(tenant) { w ->
            w.principal = ALICE
            w.batch(1) shouldBe 202
            w.principal = BOB
            w.dated(1, "T-1") shouldBe 202

            receiptsOf(tenant) shouldBe listOf(receipt("BATCH", 1, ALICE), receipt("TRANSACTIONS", 1, BOB))
        }
    }

    @Test
    fun `INV-AC-28764 a replay, a lower revision and a refusal of either operation write no receipt`() {
        receiptWorld(tenant) { w ->
            w.batch(2) shouldBe 202
            transmit(tenant)
            w.batch(3) shouldBe 202
            w.dated(2, "T-1") shouldBe 202
            val accepted =
                listOf(receipt("BATCH", 2, ALICE), receipt("BATCH", 3, ALICE), receipt("TRANSACTIONS", 2, ALICE))
            receiptsOf(tenant) shouldBe accepted
            val stored = intakeDataOf(tenant)

            w.principal = BOB
            // replays: of a stored aggregate, of a seen revision and of dated transactions
            w.batch(2) shouldBe 202
            w.batch(3) shouldBe 202
            w.dated(2, "T-1") shouldBe 202
            // a replay with another reference is answered with the original and stores nothing
            w.dated(2, "T-OTHER") shouldBe 202
            // lower revisions (ING-002)
            w.batch(1) shouldBe 409
            w.dated(1, "T-2") shouldBe 409
            // refusals of the contract (SRT-001)
            w.batch(7, taxable = "90.00") shouldBe 400
            w.dated(7, "T-3", claimable = "0.70") shouldBe 400
            // ING-022: the number cannot be built
            w.entityCode = null
            w.batch(1, key = "SK-NEW", type = AggregationBatchRequest.AggregateType.PCP) shouldBe 422
            w.entityCode = "E1"
            // ING-018: the window crosses a period boundary
            w.spans =
                PeriodSpans.Known(listOf(PeriodSpan(DAY, DAY, "A"), PeriodSpan(DAY.plusDays(1), DAY.plusDays(1), "B")))
            w.batch(1, key = "SK-CROSS", periodEnd = DAY.plusDays(1)) shouldBe 422

            receiptsOf(tenant) shouldBe accepted
            intakeDataOf(tenant) shouldBe stored
        }
    }

    @Test
    fun `INV-AC-28764 a transaction store writes its receipt only when it inserts a row, and never a second one`() {
        receiptWorld(tenant) { w ->
            w.storeRows("T-1", "T-2")
            val stored = intakeDataOf(tenant)
            receiptsOf(tenant) shouldBe listOf(receipt("TRANSACTIONS", 1, ALICE))

            w.principal = BOB
            w.storeRows("T-1", "T-2")
            w.storeRows()
            intakeDataOf(tenant) shouldBe stored

            // a call adding a row under the receipted triple fails and stores nothing (fail closed)
            shouldThrow<DataAccessException> { w.storeRows("T-1", "T-NEW") }.sqlState() shouldBe "23505"
            intakeDataOf(tenant) shouldBe stored

            // rows accepted before the migration have no receipt; a call inserting one more row writes it
            databaseExecute("delete from aggregation.intake_receipt where tenant_id = '$tenant'")
            w.storeRows("T-1", "T-NEW")
            receiptsOf(tenant) shouldBe listOf(receipt("TRANSACTIONS", 1, BOB))
        }
    }

    @Test
    fun `INV-AC-28764 of two concurrent transaction stores of one triple only one leaves rows and a receipt`() {
        receiptWorld(tenant) { first ->
            receiptWorld(tenant) { second ->
                second.principal = BOB

                val lost =
                    race(first, second, { first.storeRows("T-FIRST") }, { second.storeRows("T-SECOND") })
                        .exceptionOrNull()

                lost shouldNotBe null
                (lost as DataAccessException).sqlState() shouldBe "23505"
                databaseRows(
                    "select transaction_ref from aggregation.aggregate_transaction where tenant_id = '$tenant'",
                ) shouldBe listOf("T-FIRST")
                receiptsOf(tenant) shouldBe listOf(receipt("TRANSACTIONS", 1, ALICE))
            }
        }
    }

    @Test
    fun `INV-AC-28764 of two concurrent identical unchanged batches the second is a replay and writes no receipt`() {
        receiptWorld(tenant) { first ->
            receiptWorld(tenant) { second ->
                first.batch(1) shouldBe 202
                transmit(tenant)
                second.principal = BOB

                val raced = race(first, second, { first.batch(2) shouldBe 202 }, { second.batch(2) })

                raced.getOrThrow() shouldBe 202
                databaseRows(
                    "select source_revision::text from aggregation.seen_revision where tenant_id = '$tenant'",
                ) shouldBe listOf("2")
                receiptsOf(tenant) shouldBe listOf(receipt("BATCH", 1, ALICE), receipt("BATCH", 2, ALICE))

                // the store of a revision already seen adds nothing, and still needs a principal
                val stored = intakeDataOf(tenant)
                val compared = checkNotNull(second.aggregates.current(ENTITY, KEY))
                second.seen.recordSeen(compared, 2)
                second.principal = null
                shouldThrow<IllegalStateException> { second.seen.recordSeen(compared, 2) }
                intakeDataOf(tenant) shouldBe stored
            }
        }
    }

    /**
     * Runs [held] in a transaction of [first] that stays open until the [racing] call of [second] waits for one of its
     * locks, then commits it; the result of the racing call.
     */
    private fun <T> race(
        first: IntakeReceiptWorld,
        second: IntakeReceiptWorld,
        held: () -> Unit,
        racing: () -> T,
    ): Result<T> {
        val stored = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding =
            CompletableFuture.runAsync {
                first.runner.inTransaction {
                    held()
                    stored.countDown()
                    check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "never released" }
                }
            }
        check(stored.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the first call never stored" }
        val pid = second.connection.backendPid()
        val raced = CompletableFuture.supplyAsync { runCatching(racing) }
        try {
            awaitLockWait(pid)
        } finally {
            release.countDown()
        }
        holding.get(WAIT_SECONDS, TimeUnit.SECONDS)
        return raced.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun java.sql.Connection.backendPid(): Int =
        createStatement()
            .use { s ->
                s.executeQuery("select pg_backend_pid()").use { r ->
                    check(r.next())
                    r.getInt(1)
                }
            }.also { commit() }

    /** Returns once the backend [pid] waits for a lock of the first call: the two calls overlap. */
    private fun awaitLockWait(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        val waiting = "select count(*)::text from pg_stat_activity where pid = $pid and wait_event_type = 'Lock'"
        while (databaseRows(waiting) != listOf("1")) {
            check(System.nanoTime() < deadline) { "the second call never waited for the first" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `INV-AC-28764 the closing run, also of late transactions, writes no receipt`() {
        receiptWorld(tenant) { w ->
            w.dated(1, "T-1") shouldBe 202
            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 15))
            aggregateKeys().size shouldBe 1
            transmit(tenant)
            w.principal = ALICE
            w.dated(1, "T-LATE", key = "SK-LATE") shouldBe 202
            val accepted =
                listOf(
                    "TRANSACTIONS|$ENTITY|$KEY|1|STI|$ALICE",
                    "TRANSACTIONS|$ENTITY|SK-LATE|1|STI|$ALICE",
                ).sorted()
            receiptsOf(tenant).sorted() shouldBe accepted

            w.principal = "svc:aggregation-svc"
            w.closeWindows(LocalDate.of(2027, 3, 16))

            val keys = aggregateKeys()
            keys.size shouldBe 2
            keys shouldContain "window-late:$ENTITY:STI:$DAY:$DAY:1"
            receiptsOf(tenant).sorted() shouldBe accepted
        }
    }

    private fun aggregateKeys() =
        databaseRows("select source_key from aggregation.aggregate where tenant_id = '$tenant' order by 1")

    /**
     * Each path of an intake, prepared by `prepare` and then sent by `send` as revision `revision` of `operation`.
     */
    private class Path(
        val name: String,
        val operation: String,
        val revision: Int,
        val prepare: (IntakeReceiptWorld, UUID) -> Unit,
        val send: (IntakeReceiptWorld) -> Unit,
    )

    private val paths =
        listOf(
            Path("new batch", "BATCH", 1, { _, _ -> }, { it.batch(1) }),
            Path("replacement", "BATCH", 2, { w, _ -> w.batch(1) }, { it.batch(2, "120.00") }),
            Path(
                "supplement",
                "BATCH",
                2,
                { w, t ->
                    w.batch(1)
                    transmit(t)
                },
                { it.batch(2, "150.00") },
            ),
            Path(
                "unchanged over a transmitted aggregate",
                "BATCH",
                2,
                { w, t ->
                    w.batch(1)
                    transmit(t)
                },
                { it.batch(2) },
            ),
            Path(
                "unchanged over a supplementary",
                "BATCH",
                3,
                { w, t ->
                    w.batch(1)
                    transmit(t)
                    w.batch(2, "150.00")
                },
                { it.batch(3) },
            ),
            Path("transactions", "TRANSACTIONS", 1, { _, _ -> }, { it.dated(1, "T-1") }),
        )

    @Test
    fun `INV-AC-28764 on every path a receipt that cannot be inserted leaves no intake data`() {
        paths.forEach { path ->
            val own = UUID.randomUUID()
            receiptWorld(own) { w ->
                path.prepare(w, own)
                seedReceipt(own, path.operation, path.revision)
                val stored = intakeDataOf(own)

                val failure = shouldThrow<DataAccessException> { path.send(w) }

                (path.name to failure.sqlState()) shouldBe (path.name to "23505")
                (path.name to intakeDataOf(own)) shouldBe (path.name to stored)
            }
        }
    }

    @Test
    fun `INV-AC-28764 on every path, without a principal the intake store throws and nothing is stored`() {
        paths.forEach { path ->
            listOf(null, " ").forEach { none ->
                val own = UUID.randomUUID()
                receiptWorld(own) { w ->
                    path.prepare(w, own)
                    val stored = intakeDataOf(own)
                    w.principal = none

                    shouldThrow<IllegalStateException> { path.send(w) }

                    (path.name to intakeDataOf(own)) shouldBe (path.name to stored)
                }
            }
        }
    }

    @Test
    fun `INV-AC-28764 a failing store leaves no receipt`() {
        // the ShortCodeUsed event is not in the catalogue: the aggregate store fails after its first insert
        receiptWorld(tenant, shortCodeEvent = "com.dynokore.gst.aggregation.NotTheEvent.v1") { w ->
            shouldThrow<EmissionNotPermitted> { w.batch(1) }
            intakeDataOf(tenant) shouldBe emptyList()
        }
        // the second row breaks the amount check of aggregate_transaction
        receiptWorld(tenant) { w ->
            shouldThrow<DataAccessException> {
                w.runner.inTransaction {
                    w.storeRows("T-1")
                    w.storeRows("T-2", amount = "not-a-number")
                }
            }
            intakeDataOf(tenant) shouldBe emptyList()
        }
    }

    private fun <T> asRuntime(
        bound: UUID,
        block: (java.sql.Connection) -> T,
    ): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, bound)
            block(connection)
        }

    private fun refusedFor(sql: String): String? =
        shouldThrow<SQLException> {
            asRuntime(tenant) { c -> c.createStatement().use { it.execute(sql) } }
        }.sqlState

    @Test
    fun `INV-AC-28764 the runtime role cannot update or delete a receipt`() {
        receiptWorld(tenant) { it.batch(1) }
        val stored = intakeDataOf(tenant)

        refusedFor("update aggregation.intake_receipt set submitted_by = '$BOB'") shouldBe "42501"
        refusedFor("update aggregation.intake_receipt set recorded_at = now()") shouldBe "42501"
        refusedFor("delete from aggregation.intake_receipt") shouldBe "42501"
        refusedFor("truncate aggregation.intake_receipt") shouldBe "42501"

        intakeDataOf(tenant) shouldBe stored
        databaseRows(
            "select p from unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'," +
                "'MAINTAIN']) p where has_table_privilege('aggregation_runtime', 'aggregation.intake_receipt', p) " +
                "order by 1",
        ) shouldBe listOf("INSERT", "SELECT")
        databaseRows(
            "select p from unnest(array['UPDATE','REFERENCES']) p " +
                "where has_any_column_privilege('aggregation_runtime', 'aggregation.intake_receipt', p)",
        ) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28764 a receipt is visible to and written by its own tenant only`() {
        receiptWorld(tenant) { it.batch(1) }
        val other = UUID.randomUUID()

        asRuntime(other) { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from aggregation.intake_receipt").use {
                    check(it.next())
                    it.getInt(1)
                }
            }
        } shouldBe 0
        shouldThrow<SQLException> {
            asRuntime(other) { c ->
                c.createStatement().use {
                    it.execute(
                        "insert into aggregation.intake_receipt (tenant_id, intake_operation, entity_ref, " +
                            "source_key, source_revision, aggregate_type, submitted_by) " +
                            "values ('$tenant', 'BATCH', '$ENTITY', 'SK-X', 1, 'POS', '$BOB')",
                    )
                }
            }
        }.sqlState shouldBe "42501"
        databaseRows(
            "select col_description('aggregation.intake_receipt'::regclass, attnum) from pg_attribute " +
                "where attrelid = 'aggregation.intake_receipt'::regclass and attname = 'submitted_by'",
        ) shouldBe listOf("class: personal")
    }

    private companion object {
        const val WAIT_SECONDS = 20L
    }
}
