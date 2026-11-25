package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.application.arrival.ArrivalMetrics
import com.dynokore.gst.submission.application.arrival.ArrivalPlanning
import com.dynokore.gst.submission.application.arrival.ArrivingDocument
import com.dynokore.gst.submission.application.arrival.ArrivingDocumentFacts
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.CollaboratorUnavailableException
import com.dynokore.gst.submission.application.arrival.FiledPeriods
import com.dynokore.gst.submission.application.arrival.PriorSolutionCheck
import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.arrival.RegisteredDocument
import com.dynokore.gst.submission.application.arrival.RegisteredDocuments
import com.dynokore.gst.submission.application.arrival.ScopeBlocks
import com.dynokore.gst.submission.application.arrival.ScopeCheck
import com.dynokore.gst.submission.application.arrival.ScopeEntry
import com.dynokore.gst.submission.application.arrival.ScopeFacts
import com.dynokore.gst.submission.application.migration.ImportedPriorEvidenceStore
import com.dynokore.gst.submission.application.migration.PriorSolutionHold
import com.dynokore.gst.submission.application.migration.PriorSolutionHoldStore
import com.dynokore.gst.submission.application.migration.RaisePriorSolutionHold
import com.dynokore.gst.submission.application.ordering.CreditNoteHold
import com.dynokore.gst.submission.application.ordering.HeldVersion
import com.dynokore.gst.submission.application.ordering.HoldStore
import com.dynokore.gst.submission.application.ordering.ReleaseAloneAt
import com.dynokore.gst.submission.application.startdate.MigrationCutoverDates
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.routing.UnresolvedInput
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/**
 * The `PayloadReady` handler with the prior-solution check (INV-AC-28449), over a recording store, queue and hold
 * port. [deliver] is the consumer's transaction: every write made inside it carries its number, and a delivery that
 * throws removes all of them.
 */
class PayloadReadyHandlerPriorSolutionTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val clock = Clock.fixed(Instant.parse("2026-04-20T03:00:00Z"), ZoneOffset.UTC)
    private val held = UUID.randomUUID().toString()
    private val unaffected = UUID.randomUUID().toString()
    private val bytes = "payload".toByteArray()
    private val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class Row(
        val state: LifecycleState,
        val tx: Int,
    )

    private data class HoldRow(
        val hold: PriorSolutionHold,
        val tx: Int,
    )

    private val rows = mutableMapOf<Triple<TenantId, String, Int>, Row>()
    private val holds = mutableMapOf<Triple<TenantId, String, Int>, HoldRow>()
    private val raiseCalls = mutableListOf<Triple<TenantId, String, Int>>()
    private val queued = mutableListOf<QueueEntry>()
    private val planned = mutableListOf<String>()
    private var transaction = 0
    private val factReads = mutableListOf<Pair<String, Int>>()
    private val documents = mutableMapOf<Pair<String, Int>, ArrivingDocument>()
    private var cutoverRead: () -> LocalDate? = { LocalDate.parse("2026-04-01") }

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = t1

            override fun currentActor(): String = "submission-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private val store =
        object : SubmissionStore {
            override fun insertReady(
                tenant: TenantId,
                version: NewReadySubmission,
            ): Int {
                val key = Triple(tenant, version.docUuid, version.version)
                if (key in rows) return 0
                rows[key] = Row(LifecycleState.READY, transaction)
                return 1
            }

            override fun storedPayloadSha256(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): String = sha

            override fun moveReadyToQueued(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int {
                setState(tenant, docUuid, version, LifecycleState.QUEUED)
                return 1
            }

            override fun setState(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                state: LifecycleState,
            ) {
                val key = Triple(tenant, docUuid, version)
                rows[key] = rows.getValue(key).copy(state = state)
            }

            override fun lockForUpdate(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): LockedSubmission? = error("not an arrival step")

            override fun markSent(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                attemptCount: Int,
            ) = error("an arrival transmits nothing")

            override fun clearDueRetries(
                tenant: TenantId,
                now: Instant,
            ): List<Pair<String, Int>> = error("not an arrival step")

            override fun recordResponse(
                tenant: TenantId,
                response: AttemptResponse,
            ) = error("an arrival transmits nothing")

            override fun recordFailureClass(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                attemptNumber: Int,
                failureClass: String,
            ) = error("an arrival transmits nothing")

            override fun scheduleRetry(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                retry: RetrySchedule,
            ) = error("not an arrival step")

            override fun recordAccepted(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                at: Instant,
            ) = error("an arrival transmits nothing")

            override fun recordAcknowledgementDeadline(
                tenant: TenantId,
                docUuid: String,
                version: Int,
                deadline: Instant,
            ) = error("not an arrival step")

            override fun insertAttempt(
                tenant: TenantId,
                attempt: NewAttempt,
            ) = error("an arrival transmits nothing")
        }

    private val inFlight =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> =
                rows
                    .filterKeys { it.first == tenant && it.second == docUuid }
                    .map { (key, row) -> VersionState(key.third, row.state, SubmissionKind.TYPE_1B, key in holds) }
                    .sortedBy { it.version }

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = error("not an arrival step")
        }

    private val workQueue =
        object : WorkQueue {
            override fun enqueue(entry: QueueEntry): Long {
                queued += entry
                return queued.size.toLong()
            }

            override fun claimNext(): ClaimedItem? = error("an arrival claims nothing")

            override fun payloadOf(item: ClaimedItem): String = error("an arrival claims nothing")

            override fun complete(item: ClaimedItem) = error("an arrival claims nothing")

            override fun release(item: ClaimedItem) = error("an arrival claims nothing")
        }

    private val facts =
        object : ArrivingDocumentFacts {
            override fun of(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): ArrivingDocument? {
                factReads += docUuid to version
                return documents[docUuid to version]
            }
        }
    private val cutoverDates =
        object : MigrationCutoverDates {
            override fun record(
                tenant: TenantId,
                entityRef: String,
                date: LocalDate,
                effectiveDate: LocalDate,
            ): Boolean = error("not an arrival step")

            override fun find(
                tenant: TenantId,
                entityRef: String,
            ): LocalDate? = cutoverRead()
        }
    private val evidence =
        object : ImportedPriorEvidenceStore {
            override fun record(
                tenant: TenantId,
                record: ImportedPriorRecord,
                priorSolution: String,
            ): Boolean = error("not an arrival step")

            override fun listFor(
                tenant: TenantId,
                entityRef: String,
            ): List<ImportedPriorRecord> = emptyList()
        }
    private val holdStore =
        object : PriorSolutionHoldStore {
            override fun raise(hold: PriorSolutionHold): PriorSolutionHold {
                val key = Triple(hold.tenant, hold.docUuid, hold.version)
                raiseCalls += key
                return holds.getOrPut(key) { HoldRow(hold, transaction) }.hold
            }

            override fun open(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): PriorSolutionHold? = holds[Triple(tenant, docUuid, version)]?.hold
        }

    private val noScopeFacts =
        object : ScopeFacts {
            override fun of(
                tenant: TenantId,
                docUuid: String,
            ): ScopeEntry? = null
        }
    private val raising =
        CodeRaising(
            { null },
            object : RaisedCodes {
                override fun append(
                    tenant: TenantId,
                    raised: RaisedCode,
                    raisedAt: Instant,
                ): Boolean = error("no arrival code is expected")
            },
            object : CodeSignals {
                override fun stage(raised: RaisedCode) = error("no arrival code is expected")
            },
            clock,
        )
    private val planning =
        ArrivalPlanning(
            object : RegisteredDocuments {
                override fun find(
                    tenant: TenantId,
                    docUuid: String,
                ): RegisteredDocument? {
                    planned += docUuid
                    return null
                }
            },
            object : FiledPeriods {
                override fun isFiled(
                    entityRef: String,
                    periodRef: String,
                ): Boolean = false
            },
            object : ArrivalMetrics {
                override fun unresolvedInput(input: UnresolvedInput) = Unit

                override fun unknownRegisterEntry() = Unit
            },
        )
    private val creditNoteHold =
        CreditNoteHold(
            noScopeFacts,
            { _, _, _ -> CreditNoteOption.RELEASE_ALONE },
            { _, _ -> null },
            ReleaseAloneAt({ _, _ -> error("no credit note entry") }, ZoneOffset.UTC),
            object : HoldStore {
                override fun holdReady(
                    tenant: TenantId,
                    docUuid: String,
                    version: Int,
                ): Int = error("no credit note entry")

                override fun releaseHeld(
                    tenant: TenantId,
                    docUuid: String,
                    version: Int,
                ): Int = error("no credit note entry")

                override fun heldBy(
                    tenant: TenantId,
                    precedingDocUuid: String,
                ): List<HeldVersion> = error("no credit note entry")

                override fun held(
                    tenant: TenantId,
                    limit: Int,
                ): List<HeldVersion> = error("no credit note entry")
            },
            raising,
            clock,
            ZoneOffset.UTC,
        )

    private fun handler(arriving: Pair<String, Int>) =
        PayloadReadyHandler(
            tenants,
            store,
            SubmissionQueue(tenants, transactions, store, inFlight, workQueue),
            { PayloadReadyData(arriving.first, arriving.second, "1B", "1", bytes, sha) },
            inFlight,
            planning,
            raising,
            ScopeCheck(
                noScopeFacts,
                object : ScopeBlocks {
                    override fun blockReady(
                        tenant: TenantId,
                        docUuid: String,
                        version: Int,
                    ): Int = error("no scope disagreement")
                },
                raising,
            ),
            PriorSolutionCheck(facts, cutoverDates, evidence, inFlight, RaisePriorSolutionHold(holdStore, clock)),
            creditNoteHold,
        )

    /** One consumer transaction: [afterHandler] stands for the steps of the consumer that follow the handler. */
    private fun deliver(
        doc: String,
        version: Int = 1,
        afterHandler: () -> Unit = {},
    ): HandleOutcome {
        transaction += 1
        val rowsBefore = rows.toMap()
        val holdsBefore = holds.toMap()
        val queuedBefore = queued.toList()
        return runCatching { handler(doc to version).handle(envelope()).also { afterHandler() } }
            .onFailure {
                rows.clear()
                rows.putAll(rowsBefore)
                holds.clear()
                holds.putAll(holdsBefore)
                queued.clear()
                queued.addAll(queuedBefore)
            }.getOrThrow()
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/payload-svc",
            type = "com.dynokore.gst.payload.PayloadReady.v1",
            subject = "payload",
            time = clock.instant(),
            tenant = t1,
            entity = EntityRef("E1"),
            docUuid = null,
            transactionDate = LocalDate.parse("2026-04-10"),
            docSequence = null,
            instanceId = "i1",
            correlation = CorrelationId("c1"),
            causation = CausationId("c0"),
            dataJson = "{}",
        )

    private fun creditNote(precedingDate: String) =
        ArrivingDocument(
            entityRef = "E1",
            documentNumber = "CN-1",
            transactionDate = LocalDate.parse("2026-04-10"),
            creditNote = true,
            precedingDocumentNumber = "INV-1",
            precedingDocumentDate = LocalDate.parse(precedingDate),
            versions = listOf(LifecycleState.READY),
        )

    @Test
    fun `INV-AC-28449 a held version gets its hold row with the ready row and is not planned or enqueued`() {
        documents[held to 1] = creditNote("2026-03-31")

        deliver(held) shouldBe HandleOutcome.APPLIED

        val key = Triple(t1, held, 1)
        holds.keys shouldBe setOf(key)
        holds.getValue(key).hold.code shouldBe "SUB-017"
        holds.getValue(key).hold.entityRef shouldBe "E1"
        holds.getValue(key).tx shouldBe rows.getValue(key).tx
        rows.getValue(key).state shouldBe LifecycleState.READY
        planned shouldBe emptyList()
        queued shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 a held newer version cancels no older version`() {
        val older = Triple(t1, held, 1)
        rows[older] = Row(LifecycleState.READY, 0)
        documents[held to 2] = creditNote("2026-03-31")

        deliver(held, 2) shouldBe HandleOutcome.APPLIED

        rows.getValue(older).state shouldBe LifecycleState.READY
        holds.keys shouldBe setOf(Triple(t1, held, 2))
        queued shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 the second handling of a held version is ignored and inserts no second hold row`() {
        documents[held to 1] = creditNote("2026-03-31")
        deliver(held)

        deliver(held) shouldBe HandleOutcome.IGNORED

        raiseCalls shouldBe listOf(Triple(t1, held, 1))
        holds.size shouldBe 1
        queued shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 a document that is not affected is planned and enqueued as before with no hold row`() {
        documents[unaffected to 1] = creditNote("2026-04-01")

        deliver(unaffected) shouldBe HandleOutcome.APPLIED

        planned shouldBe listOf(unaffected)
        queued.map { it.partitionKey to it.lane } shouldBe listOf(unaffected to Lane.LIVE)
        rows.getValue(Triple(t1, unaffected, 1)).state shouldBe LifecycleState.QUEUED
        holds.size shouldBe 0
    }

    @Test
    fun `INV-AC-28449 another version of a held Doc UUID is not held`() {
        documents[held to 1] = creditNote("2026-03-31")
        documents[held to 2] = creditNote("2026-04-02")
        deliver(held, 1)

        deliver(held, 2) shouldBe HandleOutcome.APPLIED

        holds.keys shouldBe setOf(Triple(t1, held, 1))
        queued.map { it.partitionKey } shouldBe listOf(held)
        rows.getValue(Triple(t1, held, 2)).state shouldBe LifecycleState.QUEUED
    }

    @Test
    fun `INV-AC-28449 without a cutover date or an external version unavailable document facts change nothing`() {
        cutoverRead = { null }

        deliver(unaffected) shouldBe HandleOutcome.APPLIED

        factReads shouldBe emptyList()
        raiseCalls shouldBe emptyList()
        planned shouldBe listOf(unaffected)
        queued.map { it.partitionKey to it.lane } shouldBe listOf(unaffected to Lane.LIVE)
        rows.getValue(Triple(t1, unaffected, 1)).state shouldBe LifecycleState.QUEUED
    }

    @Test
    fun `INV-AC-28449 unavailable facts or a failed transaction leave no ready row, hold row or plan`() {
        // The facts port has nothing for the document.
        shouldThrow<CollaboratorUnavailableException> { deliver(held) }
        // A fact read throws.
        documents[held to 1] = creditNote("2026-03-31")
        cutoverRead = { error("unavailable") }
        shouldThrow<CollaboratorUnavailableException> { deliver(held) }

        rows.size shouldBe 0
        holds.size shouldBe 0
        raiseCalls shouldBe emptyList()
        planned shouldBe emptyList()
        queued shouldBe emptyList()

        // The hold is raised and the consumer's transaction then fails: the hold row goes with the ready row.
        cutoverRead = { LocalDate.parse("2026-04-01") }
        shouldThrow<IllegalStateException> { deliver(held) { error("the commit failed") } }
        raiseCalls shouldBe listOf(Triple(t1, held, 1))
        rows.size shouldBe 0
        holds.size shouldBe 0

        // The retry applies once.
        deliver(held) shouldBe HandleOutcome.APPLIED
        holds.size shouldBe 1
    }
}
