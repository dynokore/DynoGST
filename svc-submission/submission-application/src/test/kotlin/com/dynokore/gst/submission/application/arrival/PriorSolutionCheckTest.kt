package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.migration.ImportedPriorEvidenceStore
import com.dynokore.gst.submission.application.migration.PriorSolutionHold
import com.dynokore.gst.submission.application.migration.PriorSolutionHoldStore
import com.dynokore.gst.submission.application.migration.RaisePriorSolutionHold
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.VersionState
import com.dynokore.gst.submission.application.startdate.MigrationCutoverDates
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.migration.HoldReason
import com.dynokore.gst.submission.domain.migration.ImportedPriorRecord
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision
import com.dynokore.gst.submission.domain.migration.PriorSolutionDocument
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.DependencyUnavailable
import com.dynokore.platform.outboxandqueue.FailureClassifier
import com.dynokore.platform.outboxandqueue.FailureKind
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The prior-solution check over fakes (INV-AC-28449). */
class PriorSolutionCheckTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val cutover = LocalDate.parse("2026-04-01")
    private val raisedAt = Instant.parse("2026-04-20T03:00:00Z")
    private val documents = mutableMapOf<Triple<TenantId, String, Int>, ArrivingDocument>()
    private var cutoverRead: () -> LocalDate? = { cutover }
    private var evidenceRead: () -> List<ImportedPriorRecord> = { emptyList() }
    private var versionsRead: () -> List<LifecycleState> = { listOf(LifecycleState.READY) }
    private var factsRead: (Triple<TenantId, String, Int>) -> ArrivingDocument? = { documents[it] }
    private val asked = mutableListOf<String>()
    private val raised = mutableListOf<PriorSolutionHold>()

    private val facts =
        object : ArrivingDocumentFacts {
            override fun of(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): ArrivingDocument? {
                asked += "facts $tenant $docUuid $version"
                return factsRead(Triple(tenant, docUuid, version))
            }
        }
    private val inFlight =
        object : InFlightStore {
            override fun lockVersions(
                tenant: TenantId,
                docUuid: String,
            ): List<VersionState> {
                asked += "versions $tenant $docUuid"
                return versionsRead().mapIndexed { index, state ->
                    VersionState(index + 1, state, SubmissionKind.TYPE_1B, priorSolutionHeld = false)
                }
            }

            override fun waitingDocUuids(
                tenant: TenantId,
                limit: Int,
            ): List<String> = error("the check reads no waiting list")
        }
    private val dates =
        object : MigrationCutoverDates {
            override fun record(
                tenant: TenantId,
                entityRef: String,
                date: LocalDate,
                effectiveDate: LocalDate,
            ): Boolean = error("the check writes no cutover date")

            override fun find(
                tenant: TenantId,
                entityRef: String,
            ): LocalDate? {
                asked += "cutover $tenant $entityRef"
                return cutoverRead()
            }
        }
    private val evidence =
        object : ImportedPriorEvidenceStore {
            override fun record(
                tenant: TenantId,
                record: ImportedPriorRecord,
                priorSolution: String,
            ): Boolean = error("the check writes no evidence")

            override fun listFor(
                tenant: TenantId,
                entityRef: String,
            ): List<ImportedPriorRecord> {
                asked += "evidence $tenant $entityRef"
                return evidenceRead()
            }
        }
    private val holds =
        object : PriorSolutionHoldStore {
            override fun raise(hold: PriorSolutionHold): PriorSolutionHold {
                raised += hold
                return hold
            }

            override fun open(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): PriorSolutionHold? = null
        }
    private val raise = RaisePriorSolutionHold(holds, Clock.fixed(raisedAt, ZoneOffset.UTC))
    private val sut = PriorSolutionCheck(facts, dates, evidence, inFlight, raise)

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

    private fun put(
        doc: String,
        version: Int,
        document: ArrivingDocument,
    ) {
        documents[Triple(t1, doc, version)] = document
    }

    @Test
    fun `INV-AC-28449 a SUB-017 decision raises the hold of that version for the entity and answers Held`() {
        put("D1", 1, creditNote("2026-03-31"))

        sut.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.Held

        raised shouldBe
            listOf(
                PriorSolutionHold(
                    t1,
                    "D1",
                    1,
                    "E1",
                    HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT,
                    "SUB-017",
                    raisedAt,
                ),
            )
        asked shouldBe listOf("cutover $t1 E1", "versions $t1 D1", "facts $t1 D1 1", "evidence $t1 E1")
    }

    @Test
    fun `INV-AC-28449 a post-cutover predecessor or an entity without a cutover date is not held`() {
        put("D1", 1, creditNote("2026-04-01"))
        sut.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.NotHeld

        put("D2", 1, creditNote("2026-03-31"))
        cutoverRead = { null }
        sut.check(t1, "D2", 1, "E1") shouldBe PriorSolutionOutcome.NotHeld

        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 the imported records reach the guard and an externally submitted match raises no hold`() {
        val date = LocalDate.parse("2026-03-15")
        put("D1", 1, ArrivingDocument("E1", "INV-9", date, false, null, null, listOf(LifecycleState.READY)))
        var seen: List<ImportedPriorRecord> = emptyList()
        evidenceRead = { listOf(ImportedPriorRecord("E1", "INV-9", date, "ACK-1")) }
        val recording =
            PriorSolutionCheck(facts, dates, evidence, inFlight, raise) { _, _, imported ->
                seen = imported
                PriorSolutionDecision.ExternallySubmitted("ACK-1")
            }

        recording.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.ExternallySubmitted
        seen shouldBe evidenceRead()
        sut.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.ExternallySubmitted
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28853 only an exact pre-cutover match answers ExternallySubmitted and raises no hold`() {
        val before = LocalDate.parse("2026-03-31")
        evidenceRead = {
            listOf(
                ImportedPriorRecord("E1", "INV-9", before, "ACK-1"),
                ImportedPriorRecord("E1", "INV-9", cutover, "ACK-2"),
                ImportedPriorRecord("E1", "INV-8 ", before, "ACK-3"),
            )
        }

        fun invoice(
            number: String,
            date: LocalDate,
        ) = ArrivingDocument("E1", number, date, false, null, null, listOf(LifecycleState.READY))
        put("MATCH", 1, invoice("INV-9", before))
        put("ON-CUTOVER", 1, invoice("INV-9", cutover))
        put("SPACE", 1, invoice("INV-8", before))

        listOf("MATCH", "ON-CUTOVER", "SPACE").map { sut.check(t1, it, 1, "E1") } shouldBe
            listOf(
                PriorSolutionOutcome.ExternallySubmitted,
                PriorSolutionOutcome.NotHeld,
                PriorSolutionOutcome.NotHeld,
            )
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 the guard gets every document fact and the cutover date`() {
        val facts =
            ArrivingDocument(
                "E1",
                "CN-7",
                LocalDate.parse("2026-04-11"),
                true,
                "INV-7",
                LocalDate.parse("2026-02-02"),
                listOf(LifecycleState.CANCELLED, LifecycleState.READY),
            )
        put("D7", 2, facts)
        var seen: Pair<PriorSolutionDocument, LocalDate?>? = null
        PriorSolutionCheck(this.facts, dates, evidence, inFlight, raise) { document, date, _ ->
            seen = document to date
            PriorSolutionDecision.NotAffected
        }.check(t1, "D7", 2, "E1")

        seen shouldBe
            (
                PriorSolutionDocument(
                    "E1",
                    "CN-7",
                    LocalDate.parse("2026-04-11"),
                    true,
                    "INV-7",
                    LocalDate.parse("2026-02-02"),
                    listOf(LifecycleState.CANCELLED, LifecycleState.READY),
                ) to cutover
            )
    }

    @Test
    fun `INV-AC-28449 a hold decision with another code such as SUB-016 is not held`() {
        put("D1", 1, creditNote("2026-03-31"))
        listOf("SUB-016", "SUB-0170", "sub-017", "SUB-017 ").forEach { code ->
            val other =
                PriorSolutionCheck(facts, dates, evidence, inFlight, raise) { _, _, _ ->
                    PriorSolutionDecision.HoldForCorrection(code, HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT)
                }

            other.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.NotHeld
        }
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 the same Doc UUID with another version is not held`() {
        put("D1", 1, creditNote("2026-03-31"))
        put("D1", 2, creditNote("2026-04-02"))

        sut.check(t1, "D1", 2, "E1") shouldBe PriorSolutionOutcome.NotHeld
        raised shouldBe emptyList()
        sut.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.Held
        raised.map { it.version } shouldBe listOf(1)
    }

    @Test
    fun `INV-AC-28449 missing document facts throw the typed failure that retries as infrastructure`() {
        val thrown = shouldThrow<Throwable> { sut.check(t1, "D1", 1, "E1") }

        thrown.javaClass shouldBe CollaboratorUnavailableException::class.java
        thrown.shouldBeInstanceOf<DependencyUnavailable>()
        FailureClassifier.DEFAULT.classify(thrown) shouldBe FailureKind.INFRASTRUCTURE
        FailureClassifier.DEFAULT.classify(IllegalStateException(thrown.message)) shouldBe FailureKind.RECORD
        asked shouldBe listOf("cutover $t1 E1", "versions $t1 D1", "facts $t1 D1 1")
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 a fact read that throws is the typed failure with its cause`() {
        put("D1", 1, creditNote("2026-03-31"))
        val broken = IllegalStateException("read failed")

        fun fail(): Nothing = throw broken
        val reads =
            listOf(
                { cutoverRead = { fail() } },
                {
                    cutoverRead = { cutover }
                    versionsRead = { fail() }
                },
                {
                    versionsRead = { listOf(LifecycleState.READY) }
                    factsRead = { fail() }
                },
                {
                    factsRead = { documents[it] }
                    evidenceRead = { fail() }
                },
            )
        reads.forEach { breakRead ->
            breakRead()

            val thrown = shouldThrow<CollaboratorUnavailableException> { sut.check(t1, "D1", 1, "E1") }

            thrown.cause shouldBe broken
            FailureClassifier.DEFAULT.classify(thrown) shouldBe FailureKind.INFRASTRUCTURE
        }
        raised shouldBe emptyList()
    }

    private fun assertNotHeldWithoutDocumentFacts(vararg states: LifecycleState) {
        asked.clear()
        cutoverRead = { null }
        versionsRead = { states.toList() }

        sut.check(t1, "D1", 1, "E1") shouldBe PriorSolutionOutcome.NotHeld

        asked shouldBe listOf("cutover $t1 E1", "versions $t1 D1")
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 no cutover date and no externally submitted version is not held without document facts`() {
        assertNotHeldWithoutDocumentFacts()
        assertNotHeldWithoutDocumentFacts(LifecycleState.READY)
        val others = LifecycleState.entries - LifecycleState.EXTERNALLY_SUBMITTED
        assertNotHeldWithoutDocumentFacts(*others.toTypedArray())
    }

    @Test
    fun `INV-AC-28449 a cutover date needs the document facts also with no externally submitted version`() {
        shouldThrow<CollaboratorUnavailableException> { sut.check(t1, "D1", 1, "E1") }

        asked shouldBe listOf("cutover $t1 E1", "versions $t1 D1", "facts $t1 D1 1")
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 an older externally submitted version needs the document facts without a cutover date`() {
        cutoverRead = { null }
        versionsRead = { listOf(LifecycleState.EXTERNALLY_SUBMITTED, LifecycleState.READY) }

        shouldThrow<CollaboratorUnavailableException> { sut.check(t1, "D1", 2, "E1") }

        asked shouldBe listOf("cutover $t1 E1", "versions $t1 D1", "facts $t1 D1 2")
        raised shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28449 older acknowledged and superseded versions only are not held without a cutover date`() {
        assertNotHeldWithoutDocumentFacts(
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SUPERSEDED,
            LifecycleState.READY,
        )
    }

    @Test
    fun `INV-AC-28449 an externally submitted version with available facts is held without a cutover date`() {
        cutoverRead = { null }
        val states = listOf(LifecycleState.EXTERNALLY_SUBMITTED, LifecycleState.READY)
        versionsRead = { states }
        put("D1", 2, creditNote("2026-04-02").copy(versions = states))

        sut.check(t1, "D1", 2, "E1") shouldBe PriorSolutionOutcome.Held

        raised.map { it.version to it.reason } shouldBe listOf(2 to HoldReason.RESUBMISSION_OF_EXTERNALLY_SUBMITTED)
    }
}
