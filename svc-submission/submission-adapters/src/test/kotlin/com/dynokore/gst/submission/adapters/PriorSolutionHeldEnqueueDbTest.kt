package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqPriorSolutionHoldStore
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.migration.PriorSolutionHold
import com.dynokore.gst.submission.application.queue.EnqueueResult
import com.dynokore.gst.submission.application.queue.ReleaseResult
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.queue.WaitingSweeper
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A version with a SUB-017 prior-solution hold row, open or resolved, is never queued, released by the waiting sweep,
 * requeued by retry or replay (INV-REQ-1110, INV-AC-28852). The real adapters over the database as
 * `submission_runtime`.
 */
class PriorSolutionHeldEnqueueDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val bound = ThreadLocal<UUID>()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(checkNotNull(bound.get()))

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T {
                bound.set(tenant.value)
                try {
                    return block()
                } finally {
                    bound.remove()
                }
            }
        }
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
    private val entries = mutableListOf<QueueEntry>()
    private val workQueue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long = entries.add(entry).let { entries.size.toLong() }

            override fun claimNext(): ClaimedItem? = null

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) = Unit

            override fun release(item: ClaimedItem) = Unit
        }
    private val tenant = UUID.randomUUID()
    private val id = TenantId(tenant)
    private val versions = JooqInFlightStore(dsl)
    private val store = JooqSubmissionStore(dsl)
    private val holds = JooqPriorSolutionHoldStore(dsl)
    private val submissions = SubmissionQueue(tenants, runner, store, versions, workQueue)
    private val scope = TenantTransactions(tenants, runner, WorkloadPrincipal("submission-svc"))
    private val sweeper = WaitingSweeper({ listOf(id) }, scope, versions, submissions)
    private val requeue = JooqReplayRequeue(dsl, runner, versions, workQueue)
    private val sub = Submission.SUBMISSION_
    private val due = Instant.parse("2026-11-01T10:00:00Z")

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute("delete from submission.prior_solution_hold_resolution where tenant_id = '$tenant'")
                it.execute("delete from submission.prior_solution_hold where tenant_id = '$tenant'")
                it.execute("delete from submission.submission where tenant_id = '$tenant'")
            }
        }
    }

    private fun <T> asTenant(block: () -> T): T = tenants.bind(id, "test", block)

    private fun <T> inTx(block: () -> T): T = asTenant { runner.inTransaction(block) }

    /** Doc UUIDs are real UUIDs ([SubmissionQueue] parses them); [first] sorts before every other one. */
    private fun doc(first: Boolean = false): String =
        (if (first) "00000000" else "ffffffff") + UUID.randomUUID().toString().drop(8)

    private fun seed(
        doc: String,
        version: Int = 1,
        state: String = "READY",
        nextAttemptAt: Instant? = null,
    ) = inTx {
        dsl
            .insertInto(sub)
            .set(sub.TENANT_ID, tenant)
            .set(sub.DOC_UUID, doc)
            .set(sub.VERSION, version)
            .set(sub.STATE, state)
            .set(sub.ENTITY_REF, "E1")
            .set(sub.SUBMISSION_TYPE, "TYPE_1B")
            .set(sub.RETRY_FAILURES, 0)
            .set(sub.NEXT_ATTEMPT_AT, nextAttemptAt?.atOffset(ZoneOffset.UTC))
            .execute()
    }

    private fun hold(
        doc: String,
        version: Int = 1,
    ) = inTx {
        holds.raise(
            PriorSolutionHold(
                id,
                doc,
                version,
                "E1",
                HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT,
                "SUB-017",
                due,
            ),
        )
    }

    private fun resolve(
        doc: String,
        version: Int = 1,
    ) {
        inTx {
            connection.createStatement().use {
                it.executeUpdate(
                    "insert into submission.prior_solution_hold_resolution values " +
                        "('$tenant', '$doc', $version, 'ok', 'ev-1', 'U1', now())",
                )
            }
        }
        inTx { holds.open(id, doc, version) } shouldBe null
    }

    private fun stateOf(
        doc: String,
        version: Int = 1,
    ): String? =
        inTx {
            dsl
                .select(sub.STATE)
                .from(sub)
                .where(sub.DOC_UUID.eq(doc))
                .and(sub.VERSION.eq(version))
                .fetchOne(sub.STATE)
        }

    private fun nextAttemptAt(doc: String): Instant? =
        inTx {
            dsl
                .select(sub.NEXT_ATTEMPT_AT)
                .from(sub)
                .where(sub.DOC_UUID.eq(doc))
                .fetchOne(sub.NEXT_ATTEMPT_AT)
                ?.toInstant()
        }

    private fun waiting(limit: Int = 100): List<String> = inTx { versions.waitingDocUuids(id, limit) }

    private fun item(
        doc: String,
        version: Int,
        lane: Lane = Lane.LIVE,
    ) = QueueEntry(doc, lane, """{"docUuid":"$doc","version":$version}""")

    private fun assertQueued(vararg expected: QueueEntry) {
        entries.map { Triple(it.partitionKey, it.lane, it.payloadJson) } shouldBe
            expected.map { Triple(it.partitionKey, it.lane, it.payloadJson) }
    }

    private fun assertNeverQueued(doc: String) {
        asTenant { submissions.enqueue(doc, 1) } shouldBe EnqueueResult.PriorSolutionHeld
        asTenant { submissions.releaseNext(doc) } shouldBe ReleaseResult.NothingToRelease
        waiting() shouldBe emptyList()
        sweeper.sweep() shouldBe 0
        stateOf(doc) shouldBe "READY"
        assertQueued()
    }

    @Test
    fun `INV-AC-28852 a a version with an open hold is never queued or released`() {
        val d = doc()
        seed(d)
        hold(d)

        assertNeverQueued(d)
    }

    @Test
    fun `INV-AC-28852 b a version with a resolved hold is never queued or released`() {
        val d = doc()
        seed(d)
        hold(d)
        resolve(d)

        assertNeverQueued(d)
    }

    @Test
    fun `INV-AC-28852 c with version 1 held, version 2 is released and version 1 stays READY`() {
        val d = doc()
        seed(d, 1)
        seed(d, 2)
        hold(d, 1)

        waiting() shouldBe listOf(d)
        asTenant { submissions.releaseNext(d) } shouldBe ReleaseResult.Released(2)

        stateOf(d, 1) shouldBe "READY"
        stateOf(d, 2) shouldBe "QUEUED"
        assertQueued(item(d, 2))
    }

    @Test
    fun `INV-AC-28852 d a hold on version 2 or on another document does not hold a version without its own`() {
        val d = doc()
        val other = doc()
        seed(d, 1)
        seed(d, 2)
        hold(d, 2)

        asTenant { submissions.releaseNext(d) } shouldBe ReleaseResult.Released(1)
        seed(other)
        waiting() shouldBe listOf(other)
        sweeper.sweep() shouldBe 1

        stateOf(d, 1) shouldBe "QUEUED"
        stateOf(d, 2) shouldBe "READY"
        stateOf(other) shouldBe "QUEUED"
        assertQueued(item(d, 1), item(other, 1))
    }

    @Test
    fun `INV-AC-28852 e a held document takes no place in the waiting batch`() {
        val held = doc(first = true)
        val unheld = doc()
        seed(held)
        seed(unheld)
        hold(held)

        waiting(limit = 1) shouldBe listOf(unheld)
    }

    @Test
    fun `INV-AC-28852 f the due-retry read leaves a held row unchanged and returns only the other`() {
        val held = doc()
        val unheld = doc()
        seed(held, state = "RETRY", nextAttemptAt = due)
        seed(unheld, state = "RETRY", nextAttemptAt = due)
        hold(held)
        resolve(held)
        hold(unheld, 2)

        inTx { store.clearDueRetries(id, due) } shouldBe listOf(unheld to 1)

        nextAttemptAt(held) shouldBe due
        nextAttemptAt(unheld) shouldBe null
        stateOf(held) shouldBe "RETRY"
    }

    @Test
    fun `INV-AC-28852 g replay of a held version throws with nothing written and an unheld one is requeued`() {
        val open = doc()
        val resolved = doc()
        val unheld = doc()
        listOf(open, resolved, unheld).forEach { seed(it, state = "DEAD_LETTERED", nextAttemptAt = due) }
        hold(open)
        hold(resolved)
        resolve(resolved)
        hold(unheld, 2)

        listOf(open, resolved).forEach { d ->
            shouldThrow<IllegalStateException> { asTenant { requeue.requeue(id, d, 1) } }
            stateOf(d) shouldBe "DEAD_LETTERED"
            nextAttemptAt(d) shouldBe due
        }
        assertQueued()

        asTenant { requeue.requeue(id, unheld, 1) } shouldBe RequeueOutcome.REQUEUED
        stateOf(unheld) shouldBe "QUEUED"
        assertQueued(item(unheld, 1, Lane.REPLAY))
    }
}
