package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SubmitPackageTest {
    private class Sender(
        val order: MutableList<String>,
    ) : PackageSender {
        val calls = mutableListOf<Pair<AdapterId, List<PackageMember>>>()
        val refs = mutableListOf<String>()
        var able = true
        var failure: RuntimeException? = null
        var answer: TransportOutcome = TransportOutcome.Accepted("ack-pkg")

        override fun canSend(adapter: AdapterId): Boolean = able

        override fun send(
            adapter: AdapterId,
            packageRef: String,
            documentTypeCode: String,
            members: List<PackageMember>,
        ): TransportOutcome {
            order += "send"
            calls += adapter to members
            refs += packageRef
            failure?.let { throw it }
            return answer
        }
    }

    /** Records the order of its writes; [raced] stands for a record another call stored between find and begin. */
    private class Log(
        val order: MutableList<String>,
    ) : PackageLog {
        val records = mutableMapOf<String, PackageRecord>()
        val claims = mutableListOf<Pair<PackageSend, Instant>>()
        val completions = mutableListOf<Triple<String, TransportOutcome, Instant>>()
        var raced: PackageRecord? = null

        /** What another call stored first: [complete] keeps it and returns it. */
        var storedFirst: TransportOutcome? = null

        override fun find(packageRef: String): PackageRecord? = records[packageRef]

        override fun begin(
            send: PackageSend,
            at: Instant,
        ): PackageRecord? {
            order += "begin"
            claims += send to at
            raced?.let { records[send.packageRef] = it }
            val existing = records[send.packageRef]
            if (existing == null) records[send.packageRef] = PackageRecord(send.fingerprint, at, null)
            return existing
        }

        override fun complete(
            packageRef: String,
            outcome: TransportOutcome,
            at: Instant,
        ): TransportOutcome {
            order += "complete"
            completions += Triple(packageRef, outcome, at)
            val record = records.getValue(packageRef)
            val stored = record.outcome ?: storedFirst ?: outcome
            records[packageRef] = record.copy(outcome = stored)
            return stored
        }
    }

    /** The register with the settled outcomes and adapters in flight of single Doc refs. */
    private class Attempts(
        register: AttemptLog,
    ) : AttemptLog by register {
        val settled = mutableMapOf<DocRef, TransportOutcome>()
        val inFlight = mutableMapOf<DocRef, AdapterId>()

        override fun settledOutcome(doc: DocRef): TransportOutcome? = settled[doc]

        override fun adapterInFlight(doc: DocRef): AdapterId? = inFlight[doc]
    }

    /** The Doc UUID register: only [entityOf] is used by the use case. */
    private class Register(
        val recorded: Map<UUID, EntityRef>,
    ) : AttemptLog {
        override fun adapterInFlight(doc: DocRef): AdapterId? = null

        override fun entityOf(docUuid: UUID): EntityRef? = recorded[docUuid]

        override fun acceptedAt(doc: DocRef): Instant? = null

        override fun settledOutcome(doc: DocRef): TransportOutcome? = null

        override fun begin(
            entity: EntityRef,
            doc: DocRef,
            adapter: AdapterId,
            at: Instant,
        ): UUID = error("not used")

        override fun complete(
            attempt: UUID,
            outcome: TransportOutcome,
            at: Instant,
        ) = error("not used")

        override fun counts(
            adapter: AdapterId,
            since: Instant,
        ): AttemptCounts = AttemptCounts(0, 0)
    }

    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val e3 = EntityRef("E3")
    private val a1 = AdapterId("A1")
    private val u9 = UUID.randomUUID()
    private val order = mutableListOf<String>()
    private val sender = Sender(order)
    private val log = Log(order)
    private val attempts = Attempts(Register(mapOf(u9 to e2)))
    private val now = Instant.parse("2026-11-28T10:00:00Z")
    private val useCase =
        SubmitPackage(
            attempts,
            object : EntityAdapterAssignments {
                override fun adapterFor(entity: EntityRef): AdapterId? = if (entity == e1) a1 else null

                override fun assign(
                    entity: EntityRef,
                    adapter: AdapterId,
                ) = error("not used")
            },
            sender,
            log,
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofSeconds(60),
        )

    private fun member(
        size: Int,
        declared: Long = size.toLong(),
        uuid: UUID = UUID.randomUUID(),
    ) = PackageMember(DocRef(uuid, 1), ByteArray(size), declared)

    private fun refusedInvalid(members: List<PackageMember>) {
        shouldThrow<InvalidPackage> { useCase.submit("P-1", e1, "380", members) }
        sender.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-27499 three members of entity E1 are sent once through adapter A1 with one entry per document`() {
        val members = List(3) { member(100_000) }
        val answer = useCase.submit("P-1", e1, "380", members)
        sender.calls.size shouldBe 1
        sender.calls[0].first shouldBe a1
        sender.calls[0].second shouldBe members
        answer.members shouldBe members.map { it.doc }
        answer.outcome shouldBe TransportOutcome.Accepted("ack-pkg")
    }

    @Test
    fun `INV-AC-27499 a sum of 10000001 is refused as an invalid package and nothing is sent`() {
        refusedInvalid(listOf(member(5_000_001), member(5_000_000)))
    }

    @Test
    fun `INV-AC-27499 a sum of exactly 10000000 is accepted`() {
        val one = listOf(member(5_000_000), member(5_000_000))
        useCase.submit("P-1", e1, "380", one)
        sender.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-27499 two members with the same docUuid are refused as an invalid package and nothing is sent`() {
        val u = UUID.randomUUID()
        refusedInvalid(listOf(member(10, uuid = u), member(10, uuid = u)))
    }

    @Test
    fun `INV-AC-27499 eleven members are refused as an invalid package and nothing is sent`() {
        refusedInvalid(List(11) { member(1) })
    }

    @Test
    fun `INV-AC-27499 exactly ten members are accepted`() {
        useCase.submit("P-1", e1, "380", List(10) { member(1) }).members.size shouldBe 10
        sender.calls.size shouldBe 1
    }

    @Test
    fun `INV-AC-27499 no members are refused as an invalid package and nothing is sent`() {
        refusedInvalid(emptyList())
    }

    @Test
    fun `INV-AC-27499 a member whose Doc UUID is recorded under another entity is refused and nothing is sent`() {
        shouldThrow<DocUuidRecordedUnderOtherEntity> {
            useCase.submit(
                "P-1",
                e1,
                "380",
                listOf(member(5), member(5, uuid = u9)),
            )
        }
        sender.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-27499 a real payload one byte longer than its sizeBytes is refused and nothing is sent`() {
        refusedInvalid(listOf(member(10, declared = 9)))
    }

    @Test
    fun `INV-AC-27499 a real payload one byte shorter than its sizeBytes is refused and nothing is sent`() {
        refusedInvalid(listOf(member(10, declared = 11)))
    }

    @Test
    fun `INV-AC-27499 an entity with no connection record is refused and nothing is sent`() {
        shouldThrow<NoAdapterConfigured> { useCase.submit("P-1", e3, "380", listOf(member(5))) }
        sender.calls.size shouldBe 0
    }

    private val two = listOf(member(7), member(9))

    private val outcomes =
        listOf(
            TransportOutcome.Accepted("ack-1"),
            TransportOutcome.Rejected(listOf(FailureReason("SUB-003", null, "refused"))),
            TransportOutcome.TransientFailure,
        )

    private fun submitTwo(members: List<PackageMember> = two) = useCase.submit("P-1", e1, "380", members)

    /** Leaves P-1 as a claim for [two] with no outcome, started [age] ago, and forgets the calls that made it. */
    private fun claimed(age: Duration): PackageRecord {
        submitTwo()
        val record =
            PackageRecord(
                log.claims
                    .single()
                    .first.fingerprint,
                now.minus(age),
                null,
            )
        log.records["P-1"] = record
        forget()
        return record
    }

    private fun forget() {
        order.clear()
        sender.calls.clear()
        sender.refs.clear()
        log.claims.clear()
        log.completions.clear()
    }

    private fun nothingClaimedOrSent() {
        order shouldBe emptyList()
        log.records.size shouldBe 0
    }

    @Test
    fun `INV-AC-29527 a new package is claimed, sent once, completed and answered with the stored outcome`() {
        val stored = TransportOutcome.Accepted("ack-stored")
        log.storedFirst = stored
        val answer = submitTwo()
        order shouldBe listOf("begin", "send", "complete")
        sender.refs shouldBe listOf("P-1")
        sender.calls.single() shouldBe (a1 to two)
        val (claim, at) = log.claims.single()
        claim shouldBe PackageSend("P-1", e1, claim.fingerprint, a1, two.map { it.doc })
        claim.fingerprint.matches(Regex("[0-9a-f]{64}")) shouldBe true
        at shouldBe now
        log.completions.single() shouldBe Triple("P-1", TransportOutcome.Accepted("ack-pkg"), now)
        answer shouldBe PackageOutcome(stored, two.map { it.doc })
    }

    @Test
    fun `INV-AC-29527 a repeat after each kind of outcome returns the stored outcome and sends nothing`() {
        outcomes.forEach { outcome ->
            log.records.clear()
            attempts.settled.clear()
            sender.answer = outcome
            forget()
            submitTwo()
            // the first send settled the members: the repeat is answered before that check
            two.forEach { attempts.settled[it.doc] = outcome }
            forget()
            submitTwo() shouldBe PackageOutcome(outcome, two.map { it.doc })
            order shouldBe emptyList()
        }
    }

    private fun refusedReused(
        entity: EntityRef = e1,
        documentTypeCode: String = "380",
        members: List<PackageMember> = two,
    ) {
        shouldThrow<PackageRefReused> { useCase.submit("P-1", entity, documentTypeCode, members) }
        order shouldBe emptyList()
    }

    private fun storedPackage() {
        submitTwo()
        forget()
    }

    @Test
    fun `INV-AC-29527 a stored packageRef with one payload byte changed is refused as reused`() {
        storedPackage()
        val changed = two[1].copy(payload = two[1].payload.copyOf().also { it[0] = 1 })
        refusedReused(members = listOf(two[0], changed))
    }

    @Test
    fun `INV-AC-29527 a stored packageRef with another entity is refused as reused`() {
        storedPackage()
        refusedReused(entity = e2)
    }

    @Test
    fun `INV-AC-29527 a stored packageRef with another documentTypeCode is refused as reused`() {
        storedPackage()
        refusedReused(documentTypeCode = "381")
    }

    @Test
    fun `INV-AC-29527 a stored packageRef with the members in another order is refused as reused`() {
        storedPackage()
        refusedReused(members = two.reversed())
    }

    @Test
    fun `INV-AC-29527 a stored packageRef with another member version or Doc UUID is refused as reused`() {
        storedPackage()
        refusedReused(members = listOf(two[0], two[1].copy(doc = DocRef(two[1].doc.docUuid, 2))))
        refusedReused(members = listOf(two[0], two[1].copy(doc = DocRef(UUID.randomUUID(), 1))))
    }

    @Test
    fun `INV-AC-29527 the fingerprint keeps the entity and the documentTypeCode apart`() {
        useCase.submit("P-1", e1, "380", two)
        forget()
        refusedReused(entity = EntityRef("E13"), documentTypeCode = "80")
    }

    @Test
    fun `INV-AC-29527 the packageRef is not part of the fingerprint`() {
        useCase.submit("P-1", e1, "380", two)
        useCase.submit("P-2", e1, "380", two)
        log.claims
            .map { it.first.fingerprint }
            .toSet()
            .size shouldBe 1
    }

    @Test
    fun `INV-AC-29527 a claim with no outcome that started 60 seconds ago is refused as in flight`() {
        val record = claimed(Duration.ofSeconds(60))
        shouldThrow<PackageInFlight> { submitTwo() }
        order shouldBe emptyList()
        log.records["P-1"] shouldBe record
    }

    @Test
    fun `INV-AC-29527 a claim older than 60 seconds is completed as a transient failure and nothing is sent`() {
        claimed(Duration.ofSeconds(60).plusMillis(1))
        submitTwo() shouldBe PackageOutcome(TransportOutcome.TransientFailure, two.map { it.doc })
        order shouldBe listOf("complete")
        log.completions.single() shouldBe Triple("P-1", TransportOutcome.TransientFailure, now)
    }

    @Test
    fun `INV-AC-29527 closing a stale claim returns the outcome that is then stored`() {
        claimed(Duration.ofSeconds(61))
        log.storedFirst = TransportOutcome.Accepted("ack-late")
        submitTwo().outcome shouldBe TransportOutcome.Accepted("ack-late")
        order shouldBe listOf("complete")
    }

    private fun refusedBeforeClaim(members: List<PackageMember> = two) {
        shouldThrow<InvalidPackage> { submitTwo(members) }
        nothingClaimedOrSent()
    }

    @Test
    fun `INV-AC-29527 a member with a settled accepted outcome is refused with no claim and no send`() {
        attempts.settled[two[1].doc] = TransportOutcome.Accepted("ack-old")
        refusedBeforeClaim()
    }

    @Test
    fun `INV-AC-29527 a member with a settled rejected outcome is refused with no claim and no send`() {
        attempts.settled[two[0].doc] = outcomes[1]
        refusedBeforeClaim()
    }

    @Test
    fun `INV-AC-29527 a member in flight on adapter A2 is refused with no claim and no send`() {
        attempts.inFlight[two[1].doc] = AdapterId("A2")
        refusedBeforeClaim()
    }

    @Test
    fun `INV-AC-29527 a member in flight on the entity's adapter is sent`() {
        attempts.inFlight[two[1].doc] = a1
        submitTwo()
        order shouldBe listOf("begin", "send", "complete")
    }

    @Test
    fun `INV-AC-29527 an adapter that cannot send is refused as not registered with no claim and no send`() {
        sender.able = false
        shouldThrow<AdapterNotRegistered> { submitTwo() }
        nothingClaimedOrSent()
    }

    @Test
    fun `INV-AC-29527 a send that throws is completed as a transient failure`() {
        sender.failure = IllegalStateException("boom")
        submitTwo().outcome shouldBe TransportOutcome.TransientFailure
        order shouldBe listOf("begin", "send", "complete")
        log.completions.single() shouldBe Triple("P-1", TransportOutcome.TransientFailure, now)
    }

    /** The fingerprint of [two] under P-1, learnt from a first call whose records are then dropped. */
    private fun fingerprintOfTwo(): String {
        val fingerprint = claimed(Duration.ZERO).fingerprint
        log.records.clear()
        return fingerprint
    }

    @Test
    fun `INV-AC-29527 a claim lost to a record with another fingerprint is refused as reused`() {
        log.raced = PackageRecord("other", now, null)
        shouldThrow<PackageRefReused> { submitTwo() }
        order shouldBe listOf("begin")
    }

    @Test
    fun `INV-AC-29527 a claim lost to a record with an outcome returns it and sends nothing`() {
        val fingerprint = fingerprintOfTwo()
        outcomes.forEach { outcome ->
            log.records.clear()
            log.raced = PackageRecord(fingerprint, now, outcome)
            forget()
            submitTwo().outcome shouldBe outcome
            order shouldBe listOf("begin")
        }
    }

    @Test
    fun `INV-AC-29527 a claim lost to a record 60 seconds old is refused as in flight`() {
        log.raced = PackageRecord(fingerprintOfTwo(), now.minusSeconds(60), null)
        shouldThrow<PackageInFlight> { submitTwo() }
        order shouldBe listOf("begin")
    }

    @Test
    fun `INV-AC-29527 a claim lost to a stale record completes it as a transient failure and sends nothing`() {
        log.raced = PackageRecord(fingerprintOfTwo(), now.minusSeconds(60).minusMillis(1), null)
        submitTwo().outcome shouldBe TransportOutcome.TransientFailure
        order shouldBe listOf("begin", "complete")
    }

    @Test
    fun `INV-AC-29527 a blank packageRef or one of 41 characters is refused with no claim and no send`() {
        listOf("", "   ", "p".repeat(41)).forEach {
            shouldThrow<InvalidPackage> { useCase.submit(it, e1, "380", two) }
        }
        nothingClaimedOrSent()
    }

    @Test
    fun `INV-AC-29527 a packageRef of 40 characters is sent`() {
        useCase.submit("p".repeat(40), e1, "380", two)
        sender.refs shouldBe listOf("p".repeat(40))
    }
}
