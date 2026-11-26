package com.dynokore.gst.aggregation.application

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ListSubmittedIntakeTest {
    private val filter =
        IntakeListingFilter("PRN-00088", Instant.parse("2027-04-01T00:00:00Z"), Instant.parse("2027-04-02T00:00:00Z"))
    private val after =
        IntakeListingPosition("SK-1", filter.recordedFrom, 1, "E-A", ListedIntakeOperation.BATCH)
    private val answered = IntakeListingPage(emptyList(), after)
    private val calls = mutableListOf<List<Any?>>()
    private val reader =
        object : IntakeListingReader {
            override fun entityRefs(filter: IntakeListingFilter): Set<String> {
                calls += listOf(filter)
                return setOf("E-A", "E-B")
            }

            override fun page(
                filter: IntakeListingFilter,
                permittedEntityRefs: Set<String>,
                after: IntakeListingPosition?,
                limit: Int,
            ): IntakeListingPage {
                calls += listOf(filter, permittedEntityRefs, after, limit)
                return answered
            }
        }

    @Test
    fun `INV-AC-28765 the use case hands both reads to the port with exactly what it was given`() {
        val listing = ListSubmittedIntake(reader)

        listing.entityRefs(filter) shouldBe setOf("E-A", "E-B")
        listing.page(filter, setOf("E-B"), after, 7) shouldBe answered

        calls shouldBe listOf(listOf(filter), listOf(filter, setOf("E-B"), after, 7))
    }

    @Test
    fun `INV-AC-28765 the operations are listed BATCH before TRANSACTIONS`() {
        ListedIntakeOperation.entries.map { it.name } shouldBe listOf("BATCH", "TRANSACTIONS")
    }
}
