package com.dynokore.gst.payload.application.preissuance

import com.dynokore.gst.payload.domain.PreIssuanceFlag
import com.dynokore.gst.payload.domain.PreIssuanceInputError
import com.dynokore.gst.payload.domain.PreIssuanceReason
import com.dynokore.gst.payload.domain.PreIssuanceVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CheckPreIssuanceTest {
    private class Recording(
        private val answer: RegisterLookup,
    ) : RegisterStatusSource {
        val queries = mutableListOf<RegisterQuery>()

        override fun lookup(query: RegisterQuery): RegisterLookup {
            queries += query
            return answer
        }
    }

    private val date = LocalDate.of(2026, 3, 20)
    private val reason = PreIssuanceReason("PAY-011", "supplierGstn", true)
    private val unavailable =
        PreIssuanceCheck.Verdict(PreIssuanceVerdict.WARN, emptyList(), setOf(PreIssuanceFlag.REGISTER_UNAVAILABLE))
    private val pass = PreIssuanceCheck.Verdict(PreIssuanceVerdict.PASS, emptyList(), emptySet())

    private fun run(
        answer: RegisterLookup,
        type: String? = "TYPE_1A",
        amount: String? = "9.00",
        gstn: String = "M90054321K",
    ): Pair<PreIssuanceCheck, Recording> {
        val source = Recording(answer)
        val result = CheckPreIssuance(source).execute(gstn, "E-1", "SGD", date, type, amount)
        return result to source
    }

    private fun answered(status: String) = RegisterLookup.Answered(status, null)

    private fun assertOnce(
        source: Recording,
        gstn: String = "M90054321K",
    ) {
        assertEquals(listOf(RegisterQuery(gstn, "E-1", "SGD", date)), source.queries)
    }

    @Test
    fun `INV-AC-27317 TYPE_1A NOT_REGISTERED amount 9_00 is BLOCK with PAY-011`() {
        val (r, s) = run(answered("NOT_REGISTERED"))
        assertEquals(PreIssuanceCheck.Verdict(PreIssuanceVerdict.BLOCK, listOf(reason), emptySet()), r)
        assertOnce(s)
    }

    @Test
    fun `INV-AC-27317 TYPE_2 NOT_REGISTERED is WARN with the same reason`() {
        val (r, s) = run(answered("NOT_REGISTERED"), type = "TYPE_2")
        assertEquals(PreIssuanceCheck.Verdict(PreIssuanceVerdict.WARN, listOf(reason), emptySet()), r)
        assertOnce(s)
    }

    @Test
    fun `INV-AC-27317 REGISTERED is PASS without reasons and flags`() {
        val (r, s) = run(answered("REGISTERED"))
        assertEquals(pass, r)
        assertOnce(s)
    }

    @Test
    fun `INV-AC-27317 zero amounts with NOT_REGISTERED are PASS`() {
        for (amount in listOf("0.00", "0", "-0.00")) {
            val (r, s) = run(answered("NOT_REGISTERED"), amount = amount)
            assertEquals(pass, r, amount)
            assertOnce(s)
        }
    }

    @Test
    fun `INV-AC-27317 negative amount with NOT_REGISTERED TYPE_1A is BLOCK`() {
        val (r, s) = run(answered("NOT_REGISTERED"), amount = "-9.00")
        assertEquals(PreIssuanceCheck.Verdict(PreIssuanceVerdict.BLOCK, listOf(reason), emptySet()), r)
        assertOnce(s)
    }

    @Test
    fun `INV-AC-27317 Unavailable is WARN with REGISTER_UNAVAILABLE only`() {
        val (r, s) = run(RegisterLookup.Unavailable)
        assertEquals(unavailable, r)
        assertOnce(s)
    }

    @Test
    fun `INV-AC-27317 unlisted and look-alike statuses are WARN with REGISTER_UNAVAILABLE only`() {
        for (status in listOf("SUSPENDED", "not_registered", "NOT_REGISTERED ", "REGISTER_UNAVAILABLE", "")) {
            val (r, s) = run(answered(status))
            assertEquals(unavailable, r, "[$status]")
            assertOnce(s)
        }
    }

    @Test
    fun `INV-AC-27317 supplierGstn with surrounding spaces is passed unchanged`() {
        val (_, s) = run(answered("REGISTERED"), gstn = "  M90054321K ")
        assertOnce(s, "  M90054321K ")
    }

    @Test
    fun `INV-AC-27317 an incomplete draft is refused before the register is called`() {
        val (r1, s1) = run(answered("NOT_REGISTERED"), amount = null)
        assertEquals(PreIssuanceCheck.Refused(PreIssuanceInputError.MISSING_AMOUNT), r1)
        assertEquals(0, s1.queries.size)
        val (r2, s2) = run(answered("NOT_REGISTERED"), amount = "9,00")
        assertEquals(PreIssuanceCheck.Refused(PreIssuanceInputError.INVALID_AMOUNT), r2)
        assertEquals(0, s2.queries.size)
        val (r3, s3) = run(answered("NOT_REGISTERED"), type = "type_2")
        assertEquals(PreIssuanceCheck.Refused(PreIssuanceInputError.INVALID_SUBMISSION_TYPE), r3)
        assertEquals(0, s3.queries.size)
    }

    @Test
    fun `INV-AC-2298 D1 TYPE_1A NOT_REGISTERED 9_00 is BLOCK with PAY-011 and no flags`() {
        val (r, _) = run(answered("NOT_REGISTERED"), type = "TYPE_1A", amount = "9.00")
        assertEquals(PreIssuanceCheck.Verdict(PreIssuanceVerdict.BLOCK, listOf(reason), emptySet()), r)
    }

    @Test
    fun `INV-AC-2298 D2 TYPE_2 NOT_REGISTERED 9_00 is WARN with PAY-011`() {
        val (r, _) = run(answered("NOT_REGISTERED"), type = "TYPE_2", amount = "9.00")
        assertEquals(PreIssuanceCheck.Verdict(PreIssuanceVerdict.WARN, listOf(reason), emptySet()), r)
    }

    @Test
    fun `blank supplierGstn entityRef or currency throws IllegalArgumentException before any lookup`() {
        for (blank in listOf("", "   ")) {
            val draft =
                listOf(
                    Triple(blank, "E-1", "SGD"),
                    Triple("M90054321K", blank, "SGD"),
                    Triple("M90054321K", "E-1", blank),
                )
            for ((gstn, ref, cur) in draft) {
                val source = Recording(answered("REGISTERED"))
                val thrown =
                    runCatching { CheckPreIssuance(source).execute(gstn, ref, cur, date, "TYPE_1A", "9.00") }
                        .exceptionOrNull()
                assertEquals(IllegalArgumentException::class.java, thrown?.javaClass, "[$gstn|$ref|$cur]")
                assertEquals(0, source.queries.size)
            }
        }
    }

    @Test
    fun `supplierGstn with spaces but not blank reaches the register unchanged`() {
        val (_, s) = run(answered("REGISTERED"), gstn = " 27AAA ")
        assertOnce(s, " 27AAA ")
    }
}
