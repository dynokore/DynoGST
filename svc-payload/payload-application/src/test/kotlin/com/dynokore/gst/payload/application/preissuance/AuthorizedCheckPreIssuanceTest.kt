package com.dynokore.gst.payload.application.preissuance

import com.dynokore.gst.payload.domain.PreIssuanceVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AuthorizedCheckPreIssuanceTest {
    private class Register : RegisterStatusSource {
        val queries = mutableListOf<RegisterQuery>()

        override fun lookup(query: RegisterQuery): RegisterLookup {
            queries += query
            return RegisterLookup.Answered("REGISTERED", null)
        }
    }

    private class Authority(
        private val answer: () -> Boolean,
    ) : PreIssuanceAuthority {
        val questions = mutableListOf<Triple<PreIssuanceCaller, String, String>>()

        override fun holds(
            caller: PreIssuanceCaller,
            permission: String,
            entityRef: String,
        ): Boolean {
            questions += Triple(caller, permission, entityRef)
            return answer()
        }
    }

    private val date = LocalDate.of(2026, 3, 20)
    private val pass = PreIssuanceCheck.Verdict(PreIssuanceVerdict.PASS, emptyList(), emptySet())

    private fun run(
        kind: PreIssuanceCallerKind,
        answer: () -> Boolean,
    ): Triple<AuthorizedPreIssuance, Register, Authority> {
        val register = Register()
        val authority = Authority(answer)
        val result =
            AuthorizedCheckPreIssuance(CheckPreIssuance(register), authority)
                .execute(PreIssuanceCaller("u-1", kind), "M90054321K", "E-1", "SGD", date, "TYPE_1A", "9.00")
        return Triple(result, register, authority)
    }

    private fun assertAsked(
        authority: Authority,
        kind: PreIssuanceCallerKind,
        permission: String,
    ) {
        assertEquals(listOf(Triple(PreIssuanceCaller("u-1", kind), permission, "E-1")), authority.questions)
    }

    @Test
    fun `INV-AC-27311 HUMAN held returns the delegate result and the register is asked once`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.HUMAN) { true }
        assertEquals(AuthorizedPreIssuance.Checked(pass), r)
        assertEquals(listOf(RegisterQuery("M90054321K", "E-1", "SGD", date)), reg.queries)
        assertAsked(a, PreIssuanceCallerKind.HUMAN, "transaction.ingest")
    }

    @Test
    fun `INV-AC-27311 MACHINE held returns the delegate result and the register is asked once`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.MACHINE) { true }
        assertEquals(AuthorizedPreIssuance.Checked(pass), r)
        assertEquals(listOf(RegisterQuery("M90054321K", "E-1", "SGD", date)), reg.queries)
        assertAsked(a, PreIssuanceCallerKind.MACHINE, "machine.ingestion.channels.write")
    }

    @Test
    fun `INV-AC-27311 HUMAN not held is Forbidden and the register is asked 0 times`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.HUMAN) { false }
        assertEquals(AuthorizedPreIssuance.Forbidden, r)
        assertEquals(0, reg.queries.size)
        assertAsked(a, PreIssuanceCallerKind.HUMAN, "transaction.ingest")
    }

    @Test
    fun `INV-AC-27311 MACHINE not held is Forbidden and the register is asked 0 times`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.MACHINE) { false }
        assertEquals(AuthorizedPreIssuance.Forbidden, r)
        assertEquals(0, reg.queries.size)
        assertAsked(a, PreIssuanceCallerKind.MACHINE, "machine.ingestion.channels.write")
    }

    @Test
    fun `INV-AC-27311 authority throws for HUMAN is Forbidden and the register is asked 0 times`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.HUMAN) { throw IllegalStateException("replica down") }
        assertEquals(AuthorizedPreIssuance.Forbidden, r)
        assertEquals(0, reg.queries.size)
        assertAsked(a, PreIssuanceCallerKind.HUMAN, "transaction.ingest")
    }

    @Test
    fun `INV-AC-27311 authority throws for MACHINE is Forbidden and the register is asked 0 times`() {
        val (r, reg, a) = run(PreIssuanceCallerKind.MACHINE) { throw IllegalStateException("replica down") }
        assertEquals(AuthorizedPreIssuance.Forbidden, r)
        assertEquals(0, reg.queries.size)
        assertAsked(a, PreIssuanceCallerKind.MACHINE, "machine.ingestion.channels.write")
    }

    @Test
    fun `INV-AC-27311 permission constants equal the two keys and no look-alike is asked`() {
        assertEquals("transaction.ingest", AuthorizedCheckPreIssuance.PERSON_PERMISSION)
        assertEquals("machine.ingestion.channels.write", AuthorizedCheckPreIssuance.MACHINE_PERMISSION)
        val asked =
            PreIssuanceCallerKind.entries.flatMap { k -> run(k) { true }.third.questions.map { it.second } }
        for (bad in listOf("transaction.ingest ", "Transaction.Ingest", "machine.ingestion.channels.read")) {
            assertEquals(false, asked.contains(bad))
        }
    }
}
