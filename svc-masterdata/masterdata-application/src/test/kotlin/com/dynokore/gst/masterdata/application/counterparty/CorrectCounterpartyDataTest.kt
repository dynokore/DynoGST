package com.dynokore.gst.masterdata.application.counterparty

import com.dynokore.gst.masterdata.application.request.DirectTx
import com.dynokore.gst.masterdata.application.request.FakeParties
import com.dynokore.gst.masterdata.application.request.MutableClock
import com.dynokore.gst.masterdata.application.request.PARTY
import com.dynokore.gst.masterdata.application.request.Recorder
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeInput
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-T-27118 (INV-REQ-886, INV-AC-27115): CorrectCounterpartyData appends a one-field correction version. */
class CorrectCounterpartyDataTest {
    private val parties = FakeParties()
    private val record = RecordCounterparty(DirectTx, parties, MutableClock(), Recorder())
    private val useCase = CorrectCounterpartyData(DirectTx, parties, record)
    private val caseId = UUID.fromString("0A1B2C3D-0000-4000-8000-0000000000AB")

    private fun seed(address: PostalAddress? = PostalAddress("1 Raffles Place", "Level 5", "048616")) =
        seedInto(record, address)

    private fun seedInto(
        target: RecordCounterparty,
        address: PostalAddress? = PostalAddress("1 Raffles Place", "Level 5", "048616"),
    ) {
        target.handle(
            RecordCommand(
                "ENT-1",
                PARTY,
                CounterpartyChange(
                    listOf(IdentifierInput(IdentifierScheme.UEN, "201912345K")),
                    "Acme Pte Ltd",
                    address,
                    true,
                    listOf(SchemeInput("PEPPOL", LocalDate.of(2026, 1, 1), null, null)),
                ),
                "EV-0",
                WriteOrigin.OPERATOR,
            ),
        )
    }

    private fun correct(
        field: CorrectableField,
        value: String,
        party: PartyRef = PARTY,
    ) = useCase.handle(CorrectionCommand("ENT-1", party, caseId, field, value, "user-1"))

    private fun expected(
        before: CounterpartyContent,
        field: CorrectableField,
        value: String,
    ): CounterpartyContent =
        when (field) {
            CorrectableField.LEGAL_NAME -> before.copy(legalName = value)
            CorrectableField.ADDRESS_LINE_1 -> before.copy(address = before.address!!.copy(line1 = value))
            CorrectableField.ADDRESS_LINE_2 -> before.copy(address = before.address!!.copy(line2 = value))
            CorrectableField.POSTAL_CODE -> before.copy(address = before.address!!.copy(postalCode = value))
        }

    private fun assertRefusedWithoutVersion(result: CorrectionResult) {
        assertTrue(result is CorrectionResult.Refused, "was $result")
        assertEquals(1, parties.versions.size)
    }

    @Test
    fun `INV-AC-27115 each of the four fields appends one version where only that field differs`() {
        for (field in CorrectableField.entries) {
            val local = FakeParties()
            val rec = RecordCounterparty(DirectTx, local, MutableClock(), Recorder())
            seedInto(rec)
            val before = local.versions.single()
            val applied =
                CorrectCounterpartyData(DirectTx, local, rec)
                    .handle(CorrectionCommand("ENT-1", PARTY, caseId, field, "  New value  ", "user-1"))
                    as CorrectionResult.Applied
            assertEquals(2, local.versions.size, "$field")
            assertEquals(local.versions.last(), applied.version, "$field")
            assertEquals(expected(before.content, field, "New value"), applied.version.content, "$field")
            assertEquals(before, local.versions.first(), "$field: the earlier version is unchanged")
        }
    }

    @Test
    fun `INV-AC-27115 the command handed to the record carries the case evidence reference and the operator origin`() {
        seed()
        val commands = mutableListOf<RecordCommand>()
        val spy =
            CorrectCounterpartyData(DirectTx, parties) { command ->
                commands += command
                record.handle(command)
            }
        spy.handle(CorrectionCommand("ENT-9", PARTY, caseId, CorrectableField.POSTAL_CODE, " 049000 ", "user-1"))
        val command = commands.single()
        assertEquals("privacy-case:0a1b2c3d-0000-4000-8000-0000000000ab", command.evidenceRef)
        assertEquals(WriteOrigin.OPERATOR, command.origin)
        assertEquals("ENT-9", command.entity)
        assertEquals(PARTY, command.party)
        assertEquals(emptyList<IdentifierInput>(), command.change.identifiers)
        assertNull(command.change.arcsGstChargeAgreed)
        assertNull(command.change.approvedSchemes)
        assertNull(command.change.legalName)
        assertEquals(PostalAddress("1 Raffles Place", "Level 5", "049000"), command.change.address)
    }

    @Test
    fun `INV-AC-27115 a value with leading and trailing space is accepted and stored trimmed`() {
        seed()
        val applied = correct(CorrectableField.LEGAL_NAME, "  Gamma  ") as CorrectionResult.Applied
        assertEquals("Gamma", applied.version.content.legalName)
    }

    @Test
    fun `INV-AC-27115 a blank value is refused and appends no version`() {
        seed()
        for (field in CorrectableField.entries) {
            assertRefusedWithoutVersion(correct(field, "   "))
            assertRefusedWithoutVersion(correct(field, ""))
        }
    }

    @Test
    fun `INV-AC-27115 a legal name of 201 characters is refused and 200 is accepted`() {
        seed()
        assertRefusedWithoutVersion(correct(CorrectableField.LEGAL_NAME, "n".repeat(201)))
        assertTrue(correct(CorrectableField.LEGAL_NAME, "n".repeat(200)) is CorrectionResult.Applied)
    }

    @Test
    fun `INV-AC-27115 an address line of 201 characters is refused and 200 is accepted`() {
        seed()
        for (field in listOf(CorrectableField.ADDRESS_LINE_1, CorrectableField.ADDRESS_LINE_2)) {
            assertRefusedWithoutVersion(correct(field, "a".repeat(201)))
        }
        assertTrue(correct(CorrectableField.ADDRESS_LINE_1, "a".repeat(200)) is CorrectionResult.Applied)
    }

    @Test
    fun `INV-AC-27115 a postal code of 21 characters is refused and 20 is accepted`() {
        seed()
        assertRefusedWithoutVersion(correct(CorrectableField.POSTAL_CODE, "1".repeat(21)))
        assertTrue(correct(CorrectableField.POSTAL_CODE, "1".repeat(20)) is CorrectionResult.Applied)
    }

    @Test
    fun `INV-AC-27115 an address field with no address held is refused and appends no version`() {
        seed(address = null)
        for (field in listOf(
            CorrectableField.ADDRESS_LINE_1,
            CorrectableField.ADDRESS_LINE_2,
            CorrectableField.POSTAL_CODE,
        )) {
            assertRefusedWithoutVersion(correct(field, "x"))
        }
        assertTrue(correct(CorrectableField.LEGAL_NAME, "Still fine") is CorrectionResult.Applied)
    }

    @Test
    fun `INV-AC-27115 an unknown counterparty gives UnknownCounterparty and appends no version`() {
        assertEquals(CorrectionResult.UnknownCounterparty, correct(CorrectableField.LEGAL_NAME, "x"))
        assertEquals(0, parties.versions.size)
    }
}
