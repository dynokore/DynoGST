package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.queue.TransportResult
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The Doc UUID recorded for a document is on every attempt, across retries and a restart (INV-REQ-547). */
class DocUuidRetryDbTest {
    private val transient = TransportResult.TransientFailure("SUB-001", "timeout".toByteArray())
    private val accepted = TransportResult.Accepted("AP-1", Instant.parse("2026-11-01T10:00:05Z"), "ok".toByteArray())

    @Test
    fun `INV-AC-1686 every attempt of a document carries the Doc UUID recorded for it across two retries and a restart`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val docUuid = UUID.randomUUID().toString()
        val transport = FakeTransport(transient, transient, accepted)
        var now = Instant.parse("2026-11-01T10:00:00Z")

        SubmissionContext.online(dir).run { context ->
            val f = OutcomeFixture(context)
            f.queued(tenant, docUuid)
            val cycle = f.cycle(transport)
            cycle.runOnce() shouldBe true
            now = now.plus(RETRY_WAIT)
            f.clock.now = now
            f.dueRunner { listOf(TenantId(tenant)) }.runOnce(now)
            cycle.runOnce() shouldBe true
        }

        // the process restart: a new context over the same database
        SubmissionContext.online(dir).run { context ->
            val f = OutcomeFixture(context)
            now = now.plus(RETRY_WAIT)
            f.clock.now = now
            f.dueRunner { listOf(TenantId(tenant)) }.runOnce(now)
            f.cycle(transport).runOnce() shouldBe true

            transport.requests.map { it.docUuid } shouldBe List(ATTEMPTS) { docUuid }
            transport.requests.map { it.version } shouldBe List(ATTEMPTS) { 1 }
            val attempts = f.attempts(tenant, docUuid)
            attempts.map { it.attemptNumber } shouldBe listOf(1, 2, 3)
            attempts.map { it.docUuid } shouldBe List(ATTEMPTS) { docUuid }
            val distinct = docUuids(f, tenant)
            distinct shouldBe setOf(docUuid)
        }
    }

    /** The tenancy DSLContext runs plain statements only, so the two tables are read apart and united here. */
    private fun docUuids(
        f: OutcomeFixture,
        tenant: UUID,
    ): Set<String?> =
        f.asTenant(tenant) {
            val fromSubmission =
                f.dsl
                    .selectDistinct(Submission.SUBMISSION_.DOC_UUID)
                    .from(Submission.SUBMISSION_)
                    .fetch()
            val fromAttempts =
                f.dsl
                    .selectDistinct(SubmissionAttempt.SUBMISSION_ATTEMPT.DOC_UUID)
                    .from(SubmissionAttempt.SUBMISSION_ATTEMPT)
                    .fetch()
            (fromSubmission.map { it.value1() } + fromAttempts.map { it.value1() }).toSet()
        }

    private companion object {
        const val ATTEMPTS = 3
        val RETRY_WAIT: Duration = Duration.ofHours(1)
    }
}
