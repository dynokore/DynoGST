package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.AttemptCounts
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SubmitPackageTest {
    private class Sender : PackageSender {
        val calls = mutableListOf<Pair<AdapterId, List<PackageMember>>>()

        override fun send(
            adapter: AdapterId,
            documentTypeCode: String,
            members: List<PackageMember>,
        ): TransportOutcome {
            calls += adapter to members
            return TransportOutcome.Accepted("ack-pkg")
        }
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
    private val sender = Sender()
    private val useCase =
        SubmitPackage(
            Register(mapOf(u9 to e2)),
            object : EntityAdapterAssignments {
                override fun adapterFor(entity: EntityRef): AdapterId? = if (entity == e1) a1 else null

                override fun assign(
                    entity: EntityRef,
                    adapter: AdapterId,
                ) = error("not used")
            },
            sender,
        )

    private fun member(
        size: Int,
        declared: Long = size.toLong(),
        uuid: UUID = UUID.randomUUID(),
    ) = PackageMember(DocRef(uuid, 1), ByteArray(size), declared)

    private fun refusedInvalid(members: List<PackageMember>) {
        shouldThrow<InvalidPackage> { useCase.submit(e1, "380", members) }
        sender.calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-27499 three members of entity E1 are sent once through adapter A1 with one entry per document`() {
        val members = List(3) { member(100_000) }
        val answer = useCase.submit(e1, "380", members)
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
        useCase.submit(e1, "380", one)
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
        useCase.submit(e1, "380", List(10) { member(1) }).members.size shouldBe 10
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
        shouldThrow<NoAdapterConfigured> { useCase.submit(e3, "380", listOf(member(5))) }
        sender.calls.size shouldBe 0
    }
}
