package com.dynokore.gst.consoleBff.application.renditionevidence

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private typealias MetaReply = SampleReply<SampleMetaReply>

/** INV-AC-27771: the sample metadata join over the unchanged page join, over fake ports. */
@Suppress("LargeClass") // CS-03: INV-T-27776 puts all cases of this use case in its one declared test file
class GetEntityRenditionEvidenceWithSamplesTest {
    private data class Asked(
        val entityRef: String,
        val limit: Int,
        val cursor: String?,
    )

    private enum class Kind { UNAVAILABLE, FORBIDDEN, NOT_FOUND, INVALID }

    private val attestation =
        AttestationRecord("TAX_INVOICE", "AGENT", "identity:USR-009", Instant.parse("2026-10-18T09:30:00Z"), 1)

    private val askedAttestations = mutableListOf<Asked>()
    private val askedSamples = mutableListOf<Asked>()
    private var attestationReply: PageReply<AttestationRecord> = PageReply.Ok(EvidencePage(listOf(attestation), "a-2"))
    private var sampleReply: PageReply<SampleCheckArtefactRecord> =
        PageReply.Ok(EvidencePage(listOf(artefact(reference("chk-1"))), "s-2"))

    /** The reads asked of the metadata port, from whatever thread. */
    private val askedMeta = ConcurrentLinkedQueue<Pair<String, String>>()
    private var metaReply: (String) -> MetaReply = { ok(meta(it)) }

    private val pageJoin =
        GetEntityRenditionEvidence(
            { entityRef, limit, cursor ->
                askedAttestations += Asked(entityRef, limit, cursor)
                attestationReply
            },
            { entityRef, limit, cursor ->
                askedSamples += Asked(entityRef, limit, cursor)
                sampleReply
            },
        )

    private fun useCase(executor: Executor) =
        GetEntityRenditionEvidenceWithSamples(
            pageJoin,
            { entityRef, checkId ->
                askedMeta += entityRef to checkId
                metaReply(checkId)
            },
            executor,
        )

    private fun get(executor: Executor = Executor(Runnable::run)) =
        useCase(executor).get(SampleVector.ENTITY, LIMIT, "a-1", "s-1")

    private fun reference(checkId: String) = "${SampleVector.ENTITY}/$checkId"

    private fun artefact(
        reference: String,
        type: String = "SAMPLE_CHECK",
    ) = SampleCheckArtefactRecord(type, reference, "b".repeat(64), Instant.parse("2026-04-01T01:00:00Z"))

    private fun meta(checkId: String) = SampleVector.meta(checkId = checkId)

    private fun ok(
        meta: SampleMeta,
        standingOpen: Boolean = true,
    ): MetaReply = SampleReply.Ok(SampleMetaReply(meta, standingOpen))

    private fun pageOf(vararg references: String) {
        sampleReply = PageReply.Ok(EvidencePage(references.map { artefact(it) }, null))
    }

    private fun items(count: Int) = pageOf(*(1..count).map { reference("chk-$it") }.toTypedArray())

    /** The reads of [byCheckId] answer as given; every other read answers its own record. */
    private fun answers(vararg byCheckId: Pair<String, MetaReply>) {
        val given = byCheckId.toMap()
        metaReply = { given[it] ?: ok(meta(it)) }
    }

    private fun refusal(
        status: Int,
        detail: String = "D$status",
    ) = OwnerRefusal(status, "SRT-$status", "T$status", "BLOCK", "MANUAL", "R$status", detail)

    private fun read(
        kind: Kind,
        detail: String,
    ): MetaReply =
        when (kind) {
            Kind.UNAVAILABLE -> SampleReply.Unavailable(detail)
            Kind.FORBIDDEN -> SampleReply.Forbidden(refusal(403, detail))
            Kind.NOT_FOUND -> SampleReply.NotFound(refusal(404, detail))
            Kind.INVALID -> SampleReply.Invalid(refusal(400, detail))
        }

    private fun result(
        kind: Kind,
        detail: String,
        failures: List<Verification.Failed> = emptyList(),
    ): RenditionSampleEvidenceResult =
        when (kind) {
            Kind.UNAVAILABLE -> {
                RenditionSampleEvidenceResult.Unavailable(detail, failures)
            }

            Kind.FORBIDDEN -> {
                RenditionSampleEvidenceResult.Forbidden(refusal(403, detail), failures)
            }

            Kind.NOT_FOUND -> {
                RenditionSampleEvidenceResult.NotFound(refusal(404, detail), failures)
            }

            Kind.INVALID -> {
                RenditionSampleEvidenceResult.Invalid(refusal(400, detail), failures)
            }
        }

    /** A record of another checkId, so the identity check of the item that reads it fails. */
    private fun foreign(): MetaReply = ok(meta("chk-9"))

    private fun mismatch(checkId: String) = Verification.Failed(reference(checkId), SampleCheckRule.CHECK_ID)

    @Test
    fun `INV-AC-27771 the list composes both pages, asked once each, with the SampleMeta of each item`() {
        get() shouldBe
            RenditionSampleEvidenceResult.Composed(
                EvidencePage(listOf(attestation), "a-2"),
                EvidencePage(listOf(SampleCheckEntry(artefact(reference("chk-1")), meta("chk-1"))), "s-2"),
                true,
            )
        get().integrityFailures shouldBe emptyList()
        askedAttestations shouldBe List(2) { Asked(SampleVector.ENTITY, LIMIT, "a-1") }
        askedSamples shouldBe List(2) { Asked(SampleVector.ENTITY, LIMIT, "s-1") }
    }

    @Test
    fun `INV-AC-27771 the list gives the SampleMeta of the vector equal to the record`() {
        pageOf(reference(SampleVector.CHECK))
        val result = get().shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>()
        val record = SampleVector.record()
        result.sampleChecks.items
            .single()
            .sample shouldBe
            SampleMeta(
                record.checkId,
                record.entityRef,
                record.scenario,
                record.submissionType,
                record.sampleSha256,
                record.result,
                record.textFound,
                record.missingWording,
                record.checkedAt,
                record.checkedBy,
                record.findingCode,
            )
        askedMeta.toList() shouldBe listOf(SampleVector.ENTITY to SampleVector.CHECK)
    }

    @Test
    fun `INV-AC-27771 no class of the list result has a member named sampleContent or artefactBytes`() {
        val classes =
            RenditionSampleEvidenceResult::class.java.permittedSubclasses.toList() +
                listOf(
                    RenditionSampleEvidenceResult::class.java,
                    EvidencePage::class.java,
                    SampleCheckEntry::class.java,
                    SampleCheckArtefactRecord::class.java,
                    AttestationRecord::class.java,
                    Verification.Failed::class.java,
                    SampleMeta::class.java,
                    SampleMetaReply::class.java,
                )
        classes.size shouldBe 14
        classes.flatMap(::byteMembers) shouldBe emptyList()
        // the detector sees such a member where there is one
        byteMembers(SampleRecord::class.java).toSet() shouldBe setOf("SampleRecord.sampleContent")
    }

    private fun byteMembers(type: Class<*>): List<String> {
        val fields = type.declaredFields.map { it.name }
        val getters = type.declaredMethods.map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        return (fields + getters)
            .filter { it == "sampleContent" || it == "artefactBytes" }
            .distinct()
            .map { "${type.simpleName}.$it" }
    }

    @Test
    fun `INV-AC-27771 the list makes one metadata read per SAMPLE_CHECK item and holds no record port`() {
        items(3)
        get().shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>()
        askedMeta.toList() shouldBe (1..3).map { SampleVector.ENTITY to "chk-$it" }
        GetEntityRenditionEvidenceWithSamples::class.java.declaredConstructors
            .single()
            .parameterTypes
            .toList() shouldBe
            listOf(GetEntityRenditionEvidence::class.java, SampleMetaRead::class.java, Executor::class.java)
    }

    @Test
    fun `INV-AC-27771 an item of another artefactType is passed through without a payload read`() {
        val other = listOf("SAMPLE_CHECKS", "sample_check", "SAMPLE_CHECK ").map { artefact("not-a-reference", it) }
        sampleReply = PageReply.Ok(EvidencePage(other, null))
        val result = get().shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>()
        result.sampleChecks.items shouldBe other.map { SampleCheckEntry(it, null) }
        result.standingOpen shouldBe null
        askedMeta.toList() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27771 a sampleSha256 that matches no bytes is passed through unchanged by the list`() {
        for (stored in listOf("0".repeat(64), SampleVector.OTHER_SHA256, SampleVector.SHA256.uppercase())) {
            metaReply = { ok(SampleVector.meta(checkId = it, sampleSha256 = stored)) }
            val result = get().shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>()
            result.sampleChecks.items
                .single()
                .sample
                ?.sampleSha256 shouldBe stored
        }
    }

    @Test
    fun `INV-AC-27771 standing is that of the read of the first item of the page`() {
        items(3)
        for (first in listOf(true, false)) {
            metaReply = { ok(meta(it), (it == "chk-1") == first) }
            get().shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>().standingOpen shouldBe first
        }
    }

    @Test
    fun `INV-AC-27771 an empty sample check page makes no payload read and has no standing`() {
        sampleReply = PageReply.Ok(EvidencePage(emptyList(), null))
        get() shouldBe
            RenditionSampleEvidenceResult.Composed(
                EvidencePage(listOf(attestation), "a-2"),
                EvidencePage(emptyList(), null),
                null,
            )
        askedMeta.toList() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27771 each failed page read is relayed as its kind with no integrity failure and no payload read`() {
        val failed =
            listOf<Pair<PageReply<Nothing>, RenditionSampleEvidenceResult>>(
                PageReply.Forbidden(refusal(403)) to result(Kind.FORBIDDEN, "D403"),
                PageReply.NotFound(refusal(404)) to result(Kind.NOT_FOUND, "D404"),
                PageReply.Invalid(refusal(400)) to result(Kind.INVALID, "D400"),
                PageReply.Unavailable("owner down") to result(Kind.UNAVAILABLE, "owner down"),
            )
        val okAttestations = attestationReply
        val okSamples = sampleReply
        for ((reply, relayed) in failed) {
            attestationReply = reply
            sampleReply = okSamples
            get() shouldBe relayed
            attestationReply = okAttestations
            sampleReply = reply
            get() shouldBe relayed
        }
        askedMeta.toList() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27771 a record of another checkId or entity fails the whole page`() {
        items(3)
        val wrong =
            mapOf(
                meta("chk-9") to SampleCheckRule.CHECK_ID,
                meta("chk-1") to SampleCheckRule.CHECK_ID,
                SampleVector.meta("ENT-0002", "chk-2") to SampleCheckRule.ENTITY_REF,
            )
        for ((record, rule) in wrong) {
            answers("chk-2" to ok(record))
            get() shouldBe
                RenditionSampleEvidenceResult.IntegrityFailure(
                    listOf(Verification.Failed(reference("chk-2"), rule)),
                )
        }
    }

    @Test
    fun `INV-AC-27771 a broken reference fails the whole page and is never read`() {
        val entity = SampleVector.ENTITY
        for (broken in listOf("chk-2", "${entity}chk-2", "$entity/", "ENT-0002/chk-2", "ent-0001/chk-2", "")) {
            askedMeta.clear()
            pageOf(reference("chk-1"), broken, reference("chk-3"))
            get() shouldBe
                RenditionSampleEvidenceResult.IntegrityFailure(
                    listOf(Verification.Failed(broken, SampleCheckRule.REFERENCE)),
                )
            askedMeta.map { it.second } shouldBe listOf("chk-1", "chk-3")
        }
    }

    @Test
    fun `INV-AC-27771 one unavailable read among three gives Unavailable and no page`() {
        items(3)
        answers("chk-2" to SampleReply.Unavailable("payload down"))
        get() shouldBe result(Kind.UNAVAILABLE, "payload down")
        askedMeta.size shouldBe 3
    }

    @Test
    fun `INV-AC-27771 a Forbidden, NotFound or Invalid read alone is relayed with the owner envelope and no page`() {
        items(3)
        for (kind in listOf(Kind.FORBIDDEN, Kind.NOT_FOUND, Kind.INVALID)) {
            answers("chk-2" to read(kind, "payload"))
            get() shouldBe result(kind, "payload")
        }
    }

    @Test
    fun `INV-AC-27771 each read kind beats a failed identity check and carries it, in both positions`() {
        items(2)
        for (kind in Kind.entries) {
            for ((refusedAt, foreignAt) in POSITIONS) {
                answers(refusedAt to read(kind, "payload"), foreignAt to foreign())
                get() shouldBe result(kind, "payload", listOf(mismatch(foreignAt)))
            }
        }
    }

    @Test
    fun `INV-AC-27771 the order is Unavailable, Forbidden, NotFound, Invalid, in both positions`() {
        items(2)
        val order = listOf(Kind.UNAVAILABLE, Kind.FORBIDDEN, Kind.NOT_FOUND, Kind.INVALID)
        for ((winner, loser) in order.zipWithNext()) {
            for ((winnerAt, loserAt) in POSITIONS) {
                answers(winnerAt to read(winner, "winner"), loserAt to read(loser, "loser"))
                get() shouldBe result(winner, "winner")
            }
        }
    }

    @Test
    fun `INV-AC-27771 two reads of one kind give the first in page order`() {
        items(2)
        for (kind in Kind.entries) {
            answers("chk-1" to read(kind, "first"), "chk-2" to read(kind, "second"))
            get() shouldBe result(kind, "first")
        }
    }

    @Test
    fun `INV-AC-27771 two failed identity checks give IntegrityFailure with both in page order`() {
        items(3)
        answers("chk-1" to foreign(), "chk-3" to foreign())
        get() shouldBe
            RenditionSampleEvidenceResult.IntegrityFailure(listOf(mismatch("chk-1"), mismatch("chk-3")))
    }

    @Test
    fun `INV-AC-27771 two failed identity checks and one Unavailable read give Unavailable with both`() {
        items(3)
        val checkIds = listOf("chk-1", "chk-2", "chk-3")
        for (unavailableAt in checkIds) {
            val foreignAt = checkIds - unavailableAt
            answers(
                unavailableAt to read(Kind.UNAVAILABLE, "payload down"),
                foreignAt[0] to foreign(),
                foreignAt[1] to foreign(),
            )
            get() shouldBe result(Kind.UNAVAILABLE, "payload down", foreignAt.map(::mismatch))
        }
    }

    @Test
    fun `INV-AC-27771 an IntegrityFailure cannot be built without a failed check`() {
        runCatching { RenditionSampleEvidenceResult.IntegrityFailure(emptyList()) }
            .exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `INV-AC-27771 at most 8 payload reads run at once and the page keeps its order`() {
        val references = (1..30).map { reference("chk-$it") }
        pageOf(*references.toTypedArray())
        val entered = Semaphore(0)
        val gate = CountDownLatch(1)
        metaReply = {
            entered.release()
            check(gate.await(WAIT_SECONDS, TimeUnit.SECONDS))
            ok(meta(it))
        }
        Executors.newCachedThreadPool().use { pool ->
            val run = FutureTask { get(pool) }
            Thread.ofVirtual().start(run)
            entered.tryAcquire(MAX_IN_FLIGHT, WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            // every read is held at the gate, so a ninth entry would be a ninth read in flight
            entered.tryAcquire(QUIET_MILLIS, TimeUnit.MILLISECONDS) shouldBe false
            gate.countDown()
            val result =
                run
                    .get(WAIT_SECONDS, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<RenditionSampleEvidenceResult.Composed>()
            result.sampleChecks.items.map { it.artefact.reference } shouldBe references
            result.sampleChecks.items.map { reference(checkNotNull(it.sample).checkId) } shouldBe references
        }
        askedMeta.size shouldBe references.size
    }

    @Test
    fun `INV-AC-27771 every read is handed to the executor on the thread of the caller`() {
        items(3)
        val submitters = mutableListOf<Thread>()
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            get {
                submitters += Thread.currentThread()
                pool.execute(it)
            }
        }
        submitters shouldBe List(3) { Thread.currentThread() }
    }

    private companion object {
        const val LIMIT = 25
        const val MAX_IN_FLIGHT = 8
        const val WAIT_SECONDS = 10L
        const val QUIET_MILLIS = 300L
        val POSITIONS = listOf("chk-1" to "chk-2", "chk-2" to "chk-1")
    }
}
