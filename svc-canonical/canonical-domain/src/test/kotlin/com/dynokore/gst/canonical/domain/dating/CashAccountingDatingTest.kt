package com.dynokore.gst.canonical.domain.dating

import com.dynokore.gst.canonical.domain.dating.DatingDecision.Dated
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * A sale of an entity electing Cash Accounting is dated by its invoice issue date and no payment date or scheme
 * reaches the decision (INV-REQ-2443 behavior 2, INV-BR-93; INV-AC-27679).
 */
class CashAccountingDatingTest {
    private val issued = LocalDate.of(2026, 3, 20)
    private val may10 = PostingMoment.Zoned(OffsetDateTime.parse("2026-05-10T10:00+08:00"))
    private val forbidden = setOf("pay", "paid", "payment", "payments", "cash", "scheme")

    private fun sale(posting: PostingMoment?) = DatingInput(Direction.SALE, true, issued, posting, SINGAPORE, null)

    /** Whole tokens: split at a lower-to-upper camel boundary and at every non-alphanumeric character, lowercased. */
    private fun tokens(name: String): List<String> =
        name
            .split(Regex("(?<=\\p{Ll})(?=\\p{Lu})|[^\\p{L}\\p{Nd}]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

    private fun forbiddenIn(name: String) = tokens(name).filter { it in forbidden }

    @Test
    fun `INV-AC-27679 a sale is dated by its invoice issue date with no later moment`() {
        TransactionDating.decide(sale(null)) shouldBe Dated(issued, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-27679 a posting moment on 10 May changes nothing for a sale`() {
        val withoutMoment = TransactionDating.decide(sale(null))
        TransactionDating.decide(sale(may10)) shouldBe withoutMoment
        TransactionDating.decide(sale(may10)) shouldBe Dated(issued, DateBasis.INVOICE_DATE, null)
    }

    @Test
    fun `INV-AC-27679 DatingInput has no member named for a payment, cash or scheme`() {
        val names =
            DatingInput::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        names.isNotEmpty() shouldBe true
        names.flatMap { forbiddenIn(it) } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-27679 the name check compares whole tokens and fails closed on look-alikes`() {
        listOf("payload", "payloadSize").forEach { forbiddenIn(it) shouldBe emptyList() }
        listOf("paymentDate", "paidOn", "PAYMENT_DATE", "payDate", "Scheme", "cashBasis").forEach {
            (forbiddenIn(it).isNotEmpty()) shouldBe true
        }
    }

    @Test
    fun `INV-AC-27679 a purchase with the posting date election differs so the cases are not vacuous`() {
        val purchase = DatingInput(Direction.PURCHASE, true, issued, may10, SINGAPORE, DateBasis.POSTING_DATE)
        TransactionDating.decide(purchase) shouldBe
            Dated(LocalDate.of(2026, 5, 10), DateBasis.POSTING_DATE, null)
    }
}
