package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqInFlightStore
import com.dynokore.gst.submission.adapters.persistence.JooqReplayRequeue
import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.RetryDueScan
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A Type 1A version is never put on the work queue by the retry due-scan or the replay requeue (INV-AC-27415). */
class Type1AUnscheduledPathsDbTest {
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
    private val queue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long = entries.add(entry).let { entries.size.toLong() }

            override fun claimNext(): ClaimedItem? = null

            override fun payloadOf(item: ClaimedItem): String = error("not used")

            override fun complete(item: ClaimedItem) = Unit

            override fun release(item: ClaimedItem) = Unit
        }
    private val scope = TenantTransactions(tenants, runner, WorkloadPrincipal("submission-svc"))
    private val scan = RetryDueScan(scope, JooqSubmissionStore(dsl), queue)
    private val requeue = JooqReplayRequeue(dsl, runner, JooqInFlightStore(dsl), queue)
    private val sub = Submission.SUBMISSION_
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val tenant = UUID.randomUUID()
    private val s = UUID.randomUUID().toString().take(8)
    private val due = Instant.parse("2026-11-01T10:00:00Z")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), "test", block)

    private fun seed(
        doc: String,
        state: LifecycleState,
        kind: SubmissionKind,
        nextAttemptAt: Instant? = null,
    ) = asTenant {
        runner.inTransaction {
            dsl
                .insertInto(entry)
                .set(entry.TENANT_ID, tenant)
                .set(entry.DOC_UUID, doc)
                .set(entry.ENTITY_REF, "e-$s")
                .set(entry.PERIOD_REF, "p-$s")
                .set(entry.DUE_DATE, LocalDate.of(2026, 11, 30))
                .set(entry.IN_SCOPE, true)
                .set(entry.KIND, "INVOICE")
                .execute()
            dsl
                .insertInto(sub)
                .set(sub.TENANT_ID, tenant)
                .set(sub.DOC_UUID, doc)
                .set(sub.VERSION, 1)
                .set(sub.STATE, state.name)
                .set(sub.ENTITY_REF, "e-$s")
                .set(sub.SUBMISSION_TYPE, kind.name)
                .set(sub.RETRY_FAILURES, if (nextAttemptAt != null) 2 else 0)
                .set(sub.RETRY_FIRST_FAILURE_AT, nextAttemptAt?.atOffset(ZoneOffset.UTC))
                .set(sub.NEXT_ATTEMPT_AT, nextAttemptAt?.atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    private fun row(doc: String): Pair<String?, OffsetDateTime?> =
        asTenant {
            runner.inTransaction {
                val r =
                    dsl
                        .selectFrom(sub)
                        .where(sub.TENANT_ID.eq(tenant))
                        .and(sub.DOC_UUID.eq(doc))
                        .fetchOne()
                checkNotNull(r).let { it.state to it.nextAttemptAt }
            }
        }

    @Test
    fun `INV-AC-27415 the due retry scan leaves a RETRY Type 1A version unscheduled and unchanged`() {
        val d = "a-$s"
        seed(d, LifecycleState.RETRY, SubmissionKind.TYPE_1A, due)
        asTenant { scan.run(due) } shouldBe 0
        entries shouldBe emptyList()
        val (state, next) = row(d)
        state shouldBe LifecycleState.RETRY.name
        next?.toInstant() shouldBe due
    }

    @Test
    fun `INV-AC-27415 the due retry scan enqueues a RETRY Type 1B version of another Doc UUID once as before`() {
        val a = "a-$s"
        val b = "b-$s"
        seed(a, LifecycleState.RETRY, SubmissionKind.TYPE_1A, due)
        seed(b, LifecycleState.RETRY, SubmissionKind.TYPE_1B, due)
        asTenant { scan.run(due) } shouldBe 1
        entries.map { it.partitionKey to it.lane } shouldBe listOf(b to Lane.LIVE)
        row(b).second shouldBe null
    }

    @Test
    fun `INV-AC-27415 the replay requeue refuses a DEAD_LETTERED Type 1A version and writes nothing`() {
        val d = "a-$s"
        seed(d, LifecycleState.DEAD_LETTERED, SubmissionKind.TYPE_1A)
        val e = shouldThrow<IllegalStateException> { asTenant { requeue.requeue(TenantId(tenant), d, 1) } }
        e.javaClass shouldBe IllegalStateException::class.java
        e.message shouldBe "replay of $d version 1: submission type TYPE_1A is not on the scheduled path"
        row(d).first shouldBe LifecycleState.DEAD_LETTERED.name
        entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27415 the replay requeue requeues a DEAD_LETTERED Type 1B version of another Doc UUID as before`() {
        val d = "b-$s"
        seed(d, LifecycleState.DEAD_LETTERED, SubmissionKind.TYPE_1B)
        asTenant { requeue.requeue(TenantId(tenant), d, 1) } shouldBe RequeueOutcome.REQUEUED
        row(d).first shouldBe LifecycleState.QUEUED.name
        entries.map { it.lane } shouldBe listOf(Lane.REPLAY)
    }
}
