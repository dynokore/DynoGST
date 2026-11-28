package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.Direction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Base64

class ListIntraTenantPairsTest {
    private data class Row(
        val tenant: String,
        val periodRef: String,
        val docUuid: String,
        val record: StoredPairRecord,
    )

    /** Follows the port contract: one tenant and period, qualifying documents ascending from the given one. */
    private class FakeReader(
        private val rows: List<Row>,
    ) : PairingRecordsReader {
        val counts = mutableListOf<Int>()
        val tenants = mutableListOf<String>()

        override fun documents(
            tenant: String,
            entityRef: String,
            periodRef: String,
            fromDocUuid: String?,
            count: Int,
        ): List<StoredPairDocument> {
            counts += count
            tenants += tenant
            return rows
                .filter { it.tenant == tenant && it.periodRef == periodRef }
                .groupBy({ it.docUuid }, { it.record })
                .filter { (doc, records) ->
                    records.any { it.entityRef == entityRef } && (fromDocUuid == null || doc >= fromDocUuid)
                }.toSortedMap()
                .entries
                .take(count)
                .map { StoredPairDocument(it.key, it.value) }
        }
    }

    private fun doc(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun line(
        number: Int,
        code: String? = "SR",
        net: String? = "100.00",
        tax: String? = "9.00",
    ) = StoredPairLine(number, code, net?.let(::BigDecimal), tax?.let(::BigDecimal))

    private fun version(
        number: Int,
        acknowledged: Boolean = false,
        seller: String? = SELLER,
        buyer: String? = BUYER,
        lines: List<StoredPairLine> = listOf(line(1, net = "$number.00")),
    ) = StoredPairVersion(number, acknowledged, seller, buyer, lines)

    private fun record(
        entity: String,
        direction: Direction,
        vararg versions: StoredPairVersion,
        n: Int = 1,
    ) = Row(TENANT, PERIOD, doc(n), StoredPairRecord(entity, direction, versions.toList()))

    private fun sale(
        vararg versions: StoredPairVersion = arrayOf(version(1)),
        entity: String = "E1",
        n: Int = 1,
    ) = record(entity, Direction.SALE, *versions, n = n)

    private fun purchase(
        vararg versions: StoredPairVersion = arrayOf(version(1)),
        entity: String = "E2",
        n: Int = 1,
    ) = record(entity, Direction.PURCHASE, *versions, n = n)

    private fun view(
        vararg rows: Row,
        entity: String = "E1",
        limit: Int? = null,
        cursor: String? = null,
    ) = ListIntraTenantPairs(FakeReader(rows.toList())).execute(TENANT, entity, PERIOD, limit, cursor)

    private fun sideOf(
        entity: String,
        version: Int,
    ) = PairSide(entity, version, listOf(PairLine(1, "SR", BigDecimal("$version.00"), BigDecimal("9.00"))))

    private fun assertUnpairedAlone(
        own: Row,
        vararg others: Row,
        side: UnpairedSide = UnpairedSide.SUPPLY,
    ) {
        val result = view(own, *others)
        result.items.shouldBeEmpty()
        result.unpaired.map { Triple(it.docUuid, it.side, it.entry.entityRef) } shouldBe
            listOf(Triple(own.docUuid, side, own.record.entityRef))
        result.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-28554 a pair with differing figures is paired and nothing is flagged`() {
        val supply = sale(version(1, lines = listOf(line(1, "SR", "100.00", "9.00"))))
        val bought = purchase(version(3, lines = listOf(line(1, "TX", "70.50", "1.25"), line(2, "TX", "1", "0"))))

        val result = view(supply, bought)

        result shouldBe
            IntraTenantPairsView(
                listOf(
                    IntraTenantPair(
                        doc(1),
                        PairSide("E1", 1, listOf(PairLine(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")))),
                        PairSide(
                            "E2",
                            3,
                            listOf(
                                PairLine(1, "TX", BigDecimal("70.50"), BigDecimal("1.25")),
                                PairLine(2, "TX", BigDecimal("1"), BigDecimal("0")),
                            ),
                        ),
                    ),
                ),
                emptyList(),
                null,
            )
    }

    @Test
    fun `INV-AC-28554 the pair is listed for the purchasing entity too`() {
        val result = view(sale(), purchase(), entity = "E2")

        result.items shouldBe listOf(IntraTenantPair(doc(1), sideOf("E1", 1), sideOf("E2", 1)))
        result.unpaired.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-28554 a record with no counterpart is unpaired with its side`() {
        view(sale()).unpaired shouldBe listOf(IntraTenantUnpaired(doc(1), UnpairedSide.SUPPLY, sideOf("E1", 1)))
        view(purchase(entity = "E1")).unpaired shouldBe
            listOf(IntraTenantUnpaired(doc(1), UnpairedSide.PURCHASE, sideOf("E1", 1)))
        assertUnpairedAlone(sale(), purchase(n = 2))
    }

    @Test
    fun `INV-AC-28554 a differing seller or buyer UEN does not pair`() {
        listOf(
            version(1, seller = "201912345Z"),
            version(1, buyer = "202054321Z"),
            version(1, seller = SELLER.lowercase()),
            version(1, buyer = "$BUYER "),
            version(1, seller = BUYER, buyer = SELLER),
        ).forEach { other ->
            assertUnpairedAlone(sale(), purchase(other))
            assertUnpairedAlone(purchase(other, entity = "E1"), sale(entity = "E2"), side = UnpairedSide.PURCHASE)
        }
    }

    @Test
    fun `INV-AC-28554 a null UEN on one side or on both does not pair`() {
        listOf(version(1, seller = null), version(1, buyer = null), version(1, seller = null, buyer = null))
            .forEach { without ->
                assertUnpairedAlone(sale(without), purchase())
                assertUnpairedAlone(sale(), purchase(without))
                assertUnpairedAlone(sale(without), purchase(without))
            }
    }

    @Test
    fun `INV-AC-28554 the two records of one entity do not pair and both are unpaired`() {
        val result = view(sale(), purchase(entity = "E1"))

        result.items.shouldBeEmpty()
        result.unpaired.map { it.side }.toSet() shouldBe setOf(UnpairedSide.SUPPLY, UnpairedSide.PURCHASE)
        result.unpaired.size shouldBe 2
    }

    @Test
    fun `INV-AC-28554 two records of the same direction do not pair`() {
        assertUnpairedAlone(sale(), sale(entity = "E2"))
        assertUnpairedAlone(purchase(entity = "E1"), purchase(), side = UnpairedSide.PURCHASE)
    }

    @Test
    fun `INV-AC-28554 a record of another tenant or period does not pair`() {
        val otherTenant = purchase().copy(tenant = "T2")
        val otherPeriod = purchase().copy(periodRef = "P2")
        assertUnpairedAlone(sale(), otherTenant)
        assertUnpairedAlone(sale(), otherPeriod)
        val reader = FakeReader(listOf(sale(), otherTenant))

        ListIntraTenantPairs(reader).execute(TENANT, "E1", PERIOD, null, null)

        reader.tenants shouldBe listOf(TENANT)
    }

    @Test
    fun `INV-AC-28554 entries that involve only other entities are not listed`() {
        val result = view(sale(), purchase(), purchase(entity = "E3"), sale(entity = "E4"), entity = "E3")

        result.items.map { it.supply.entityRef to it.purchase.entityRef } shouldBe
            listOf("E1" to "E3", "E4" to "E3")
        result.unpaired.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-28554 every matching counterpart forms its own pair`() {
        val result = view(sale(), purchase(entity = "E3"), purchase())

        result.items.map { it.purchase.entityRef } shouldBe listOf("E2", "E3")
        result.unpaired.shouldBeEmpty()
    }

    /** Runs a case for the record under test as a paired record and as an unpaired one. */
    private fun assertEffective(
        expected: Int,
        vararg versions: StoredPairVersion,
    ) {
        val paired = view(sale(*versions), purchase())
        paired.items.map { it.supply } shouldBe listOf(sideOf("E1", expected))
        paired.unpaired.shouldBeEmpty()
        val asPurchase = view(sale(), purchase(*versions))
        asPurchase.items.map { it.purchase } shouldBe listOf(sideOf("E2", expected))
        val alone = view(sale(*versions))
        alone.items.shouldBeEmpty()
        alone.unpaired.map { it.entry } shouldBe listOf(sideOf("E1", expected))
    }

    @Test
    fun `INV-AC-28554 an acknowledged v1 wins over a newer unacknowledged v2 and only its lines are carried`() {
        assertEffective(1, version(1, acknowledged = true), version(2))
        assertEffective(1, version(2), version(1, acknowledged = true))
    }

    @Test
    fun `INV-AC-28554 of two unacknowledged versions the highest is effective`() {
        assertEffective(2, version(1), version(2))
        assertEffective(2, version(2), version(1))
    }

    @Test
    fun `INV-AC-28554 of two acknowledged versions the highest is effective`() {
        assertEffective(2, version(1, acknowledged = true), version(2, acknowledged = true))
        assertEffective(2, version(2, acknowledged = true), version(1, acknowledged = true))
    }

    @Test
    fun `INV-AC-28554 a UEN that matches only on a version that is not effective does not pair`() {
        listOf(
            arrayOf(version(1, acknowledged = true, seller = "X"), version(2)),
            arrayOf(version(1), version(2, seller = "X")),
            arrayOf(version(1, acknowledged = true), version(2, acknowledged = true, buyer = "X")),
            arrayOf(version(1), version(2, buyer = null)),
        ).forEach { versions ->
            assertUnpairedAlone(sale(*versions), purchase())
            assertUnpairedAlone(sale(), purchase(*versions))
        }
    }

    @Test
    fun `INV-AC-28554 lines of different tax codes stay separate entries in line number order`() {
        val lines =
            listOf(
                line(3, "SR", "30.00", "2.70"),
                line(1, "SR", "100.00", "9.00"),
                line(2, "ZR", "40.00", "0.00"),
            )
        val expected =
            listOf(
                PairLine(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")),
                PairLine(2, "ZR", BigDecimal("40.00"), BigDecimal("0.00")),
                PairLine(3, "SR", BigDecimal("30.00"), BigDecimal("2.70")),
            )

        val paired = view(sale(version(1, lines = lines)), purchase(version(1, lines = lines)))
        val alone = view(sale(version(1, lines = lines)))

        paired.items
            .single()
            .supply.lines shouldBe expected
        paired.items
            .single()
            .purchase.lines shouldBe expected
        alone.unpaired
            .single()
            .entry.lines shouldBe expected
    }

    @Test
    fun `INV-AC-28554 a stored line with a null tax code or amount fails the whole read`() {
        listOf(line(2, code = null), line(2, net = null), line(2, tax = null)).forEach { broken ->
            val bad = version(1, lines = listOf(line(1), broken))
            shouldThrow<IllegalStateException> { view(sale(bad)) }
            shouldThrow<IllegalStateException> { view(sale(), purchase(bad)) }
            // A healthy document does not hide it, nor does a version that is not effective.
            shouldThrow<IllegalStateException> { view(sale(), sale(bad, n = 2)) }
            shouldThrow<IllegalStateException> { view(sale(bad, version(2))) }
        }
    }

    @Test
    fun `INV-AC-28554 an empty stored tax code is carried as it is stored`() {
        val result = view(sale(version(1, lines = listOf(line(1, code = "")))))

        result.unpaired
            .single()
            .entry.lines
            .single()
            .taxCode shouldBe ""
    }

    @Test
    fun `INV-AC-28554 a reader answer that breaks the port contract fails the read`() {
        fun readOf(vararg documents: StoredPairDocument) =
            ListIntraTenantPairs { _, _, _, _, _ -> documents.toList() }.execute(TENANT, "E1", PERIOD, null, null)
        val own = sale().record
        val good = StoredPairDocument(doc(1), listOf(own))

        shouldThrow<IllegalStateException> { readOf(StoredPairDocument(doc(2), listOf(own)), good) }
        shouldThrow<IllegalStateException> { readOf(good, good) }
        shouldThrow<IllegalStateException> { readOf(StoredPairDocument(doc(1), listOf(purchase().record))) }
        shouldThrow<IllegalStateException> { readOf(StoredPairDocument(doc(1), listOf(own, own))) }
        shouldThrow<IllegalStateException> { view(sale(version(1), version(1))) }
        shouldThrow<IllegalStateException> { view(sale(*emptyArray())) }
    }

    /** Doc 1: two pairs and an unpaired purchase of E1. Doc 2: unpaired supply. Doc 3: one pair. */
    private fun mixed() =
        arrayOf(
            sale(),
            purchase(),
            purchase(entity = "E3"),
            purchase(version(1, seller = "X"), entity = "E1"),
            sale(version(1, lines = listOf(line(1), line(2), line(3))), n = 2),
            sale(n = 3),
            purchase(n = 3),
        )

    private fun keys(view: IntraTenantPairsView) =
        view.items.map { "${it.docUuid} ${it.supply.entityRef}>${it.purchase.entityRef}" } +
            view.unpaired.map { "${it.docUuid} ${it.side}" }

    @Test
    fun `INV-AC-28554 one cursor walks both lists and every entry counts once`() {
        val all = keys(view(*mixed()))
        all.size shouldBe 5

        (1..6).forEach { size ->
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = view(*mixed(), limit = size, cursor = cursor)
                val entries = page.items.size + page.unpaired.size
                entries shouldBe minOf(size, all.size - seen.size)
                seen += keys(page)
                cursor = page.nextCursor
                pages++
            } while (cursor != null)
            seen.sorted() shouldBe all.sorted()
            pages shouldBe (all.size + size - 1) / size
        }
    }

    @Test
    fun `INV-AC-28554 the default limit is 100 and 500 is the maximum with no clamping`() {
        val rows = (1..501).map { sale(n = it) }
        val reader = FakeReader(rows)
        val list = ListIntraTenantPairs(reader)

        val first = list.execute(TENANT, "E1", PERIOD, null, null)
        val largest = list.execute(TENANT, "E1", PERIOD, 500, null)
        val rest = list.execute(TENANT, "E1", PERIOD, 500, largest.nextCursor)

        first.unpaired.size shouldBe 100
        first.nextCursor shouldNotBe null
        largest.unpaired.size shouldBe 500
        rest.unpaired.map { it.docUuid } shouldBe listOf(doc(501))
        rest.nextCursor shouldBe null
        reader.counts shouldBe listOf(102, 502, 502)
        listOf(0, -1, 501, Int.MAX_VALUE).forEach {
            shouldThrow<IllegalArgumentException> { list.execute(TENANT, "E1", PERIOD, it, null) }
        }
        reader.counts.size shouldBe 3
    }

    @Test
    fun `INV-AC-28554 entity references with separator characters survive the cursor`() {
        val rows =
            arrayOf(sale(entity = "E.|1"), purchase(entity = "a.b"), purchase(entity = "a|c"))

        val first = view(*rows, entity = "E.|1", limit = 1)
        val second = view(*rows, entity = "E.|1", limit = 1, cursor = first.nextCursor)

        (first.items + second.items).map { it.purchase.entityRef } shouldBe listOf("a.b", "a|c")
        second.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-28554 a cursor used with another tenant, entity or period is refused before any read`() {
        val cursor = checkNotNull(view(sale(), sale(n = 2), limit = 1).nextCursor)
        val reader = FakeReader(emptyList())
        val list = ListIntraTenantPairs(reader)

        listOf(
            Triple("T2", "E1", PERIOD),
            Triple(TENANT, "E3", PERIOD),
            Triple(TENANT, "E1", "P2"),
            // The same characters, split differently between the filters.
            Triple(TENANT + "E", "1", PERIOD),
            Triple(TENANT, "E1P", "1"),
        ).forEach { (tenant, entity, period) ->
            shouldThrow<IllegalArgumentException> { list.execute(tenant, entity, period, null, cursor) }
        }
        reader.counts.shouldBeEmpty()

        list.execute(TENANT, "E1", PERIOD, null, cursor)
        reader.counts.size shouldBe 1
    }

    @Test
    fun `INV-AC-28554 an unreadable cursor is refused before any read`() {
        fun cursorOf(vararg parts: String) =
            parts.joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
        val reader = FakeReader(emptyList())
        // The binding of a real cursor for the same filters, so that each case below is refused for its own defect.
        val minted = checkNotNull(view(sale(), sale(n = 2), limit = 1).nextCursor)
        val bound = String(Base64.getUrlDecoder().decode(minted.substringBefore('.')))
        ListIntraTenantPairs(FakeReader(emptyList()))
            .execute(TENANT, "E1", PERIOD, null, cursorOf(bound, doc(1), "E1", ""))
            .nextCursor shouldBe null
        listOf(
            "",
            "!!!",
            "a.b.c",
            cursorOf(bound, doc(1)),
            cursorOf(bound, doc(1), "E1"),
            cursorOf(bound, doc(1), "E1", "E2", "E3"),
            cursorOf(doc(1), "E1", ""),
            cursorOf("", doc(1), "E1", ""),
            cursorOf(bound, "not-a-uuid", "E1", ""),
            cursorOf(bound, "${doc(1)}x", "E1", ""),
            cursorOf(bound, doc(1), "", ""),
        ).forEach {
            shouldThrow<IllegalArgumentException> {
                ListIntraTenantPairs(reader).execute(TENANT, "E1", PERIOD, null, it)
            }
        }
        reader.counts.shouldBeEmpty()
    }

    private companion object {
        const val TENANT = "T1"
        const val PERIOD = "P1"
        const val SELLER = "201912345A"
        const val BUYER = "202054321B"
    }
}
