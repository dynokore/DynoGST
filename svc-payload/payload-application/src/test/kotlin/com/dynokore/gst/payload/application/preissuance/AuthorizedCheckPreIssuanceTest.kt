package com.dynokore.gst.payload.application.preissuance

import com.dynokore.gst.payload.domain.PreIssuanceVerdict
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

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

    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000042"))
    private val human = Principal("u-1", PrincipalKind.HUMAN)
    private val machine =
        MachinePrincipalFixtures.machine("u-1", tenant, MachineEntityCoverage.Entity("E-1"), "ERP_CONNECTOR")

    private fun principalOf(kind: PreIssuanceCallerKind): Principal =
        if (kind == PreIssuanceCallerKind.MACHINE) machine else human

    private fun runWith(
        principal: Principal,
        answer: () -> Boolean,
    ): Triple<AuthorizedPreIssuance, Register, Authority> {
        val register = Register()
        val authority = Authority(answer)
        val result =
            AuthorizedCheckPreIssuance(CheckPreIssuance(register), authority)
                .execute(PreIssuanceCaller(principal), "M90054321K", "E-1", "SGD", date, "TYPE_1A", "9.00")
        return Triple(result, register, authority)
    }

    private fun run(
        kind: PreIssuanceCallerKind,
        answer: () -> Boolean,
    ): Triple<AuthorizedPreIssuance, Register, Authority> = runWith(principalOf(kind), answer)

    private fun assertAsked(
        authority: Authority,
        kind: PreIssuanceCallerKind,
        permission: String,
    ) {
        assertEquals(listOf(Triple(PreIssuanceCaller(principalOf(kind)), permission, "E-1")), authority.questions)
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

    @Test
    fun `INV-AC-28421 MACHINE principal is passed through as the same instance with the machine key`() {
        val (_, _, a) = runWith(machine) { true }
        val (caller, permission, entity) = a.questions.single()
        assertSame(machine, caller.principal)
        assertEquals("machine.ingestion.channels.write", permission)
        assertEquals("E-1", entity)
        assertEquals(PreIssuanceCallerKind.MACHINE, caller.kind)
        assertEquals("u-1", caller.id)
    }

    @Test
    fun `INV-AC-28421 HUMAN principal is passed through as the same instance with the person key`() {
        val (_, _, a) = runWith(human) { true }
        val (caller, permission, _) = a.questions.single()
        assertSame(human, caller.principal)
        assertEquals("transaction.ingest", permission)
        assertEquals(PreIssuanceCallerKind.HUMAN, caller.kind)
    }

    @Test
    fun `INV-AC-28421 CONSENT_AGENT principal is HUMAN kind with the person key`() {
        val agent = Principal("u-1", PrincipalKind.CONSENT_AGENT)
        val (_, _, a) = runWith(agent) { true }
        val (caller, permission, _) = a.questions.single()
        assertSame(agent, caller.principal)
        assertEquals(PreIssuanceCallerKind.HUMAN, caller.kind)
        assertEquals("transaction.ingest", permission)
    }

    @Test
    fun `INV-AC-28421 recorded MACHINE principal keeps tenant coverage and machine type`() {
        val (_, _, a) = runWith(machine) { true }
        val recorded =
            a.questions
                .single()
                .first.principal
        assertEquals(tenant, recorded.tenant)
        assertEquals(MachineEntityCoverage.Entity("E-1"), recorded.entityCoverage)
        assertEquals("ERP_CONNECTOR", recorded.machineType)
    }

    @Test
    fun `INV-AC-28421 MACHINE caller does not equal a HUMAN caller with the same id`() {
        val (_, _, a) = runWith(machine) { true }
        assertNotEquals(PreIssuanceCaller(Principal("u-1", PrincipalKind.HUMAN)), a.questions.single().first)
    }
}
