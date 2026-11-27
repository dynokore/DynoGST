package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.platform.integrationcanon.SpecVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** INV-AC-26560, over synthetic releases and the dates of 2000 only. */
class ConfiguredAcceptanceCalendarsTest {
    private val synthA =
        """{"version": "SYNTH-A", "acceptedFrom": "2000-01-01", "acceptedTo": "2000-06-30"}"""
    private val synthB = """{"version": "SYNTH-B", "acceptedFrom": "2000-07-01"}"""

    private fun calendar(
        family: String = "PINT_SG",
        releaseOrder: String = """["SYNTH-A", "SYNTH-B"]""",
        entries: String = "[$synthA, $synthB]",
    ) = """{"family": "$family", "releaseOrder": $releaseOrder, "entries": $entries}"""

    private fun document(vararg calendars: String) = """{"calendars": [${calendars.joinToString()}]}"""

    private fun refused(
        text: String,
        vararg named: String,
    ): AcceptanceCalendarInvalid {
        val thrown =
            assertThrows(AcceptanceCalendarInvalid::class.java) { ConfiguredAcceptanceCalendars.parse(text) }
        named.forEach { assertTrue(thrown.message!!.contains(it), "'${thrown.message}' does not name $it") }
        return thrown
    }

    private fun assertNoCalendar(text: String?) {
        val calendars = ConfiguredAcceptanceCalendars.parse(text)
        SpecificationFamily.entries.forEach { assertNull(calendars.calendarOf(it)) }
    }

    @Test
    fun `INV-AC-26560 case 1 a valid two-release document gives the calendar of its family only`() {
        val calendars = ConfiguredAcceptanceCalendars.parse(document(calendar()))

        val pint = calendars.calendarOf(SpecificationFamily.PINT_SG)!!
        assertEquals(listOf(SpecVersion("SYNTH-A")), pint.acceptedOn(LocalDate.of(2000, 2, 1)))
        assertEquals(listOf(SpecVersion("SYNTH-B")), pint.acceptedOn(LocalDate.of(2000, 7, 15)))
        assertNull(calendars.calendarOf(SpecificationFamily.SG_PEPPOL_BIS))
    }

    @Test
    fun `INV-AC-26560 case 2 a null text gives no calendar for any family`() {
        assertNoCalendar(null)
    }

    @Test
    fun `INV-AC-26560 case 3 a blank text gives no calendar for any family`() {
        assertNoCalendar(" \n\t ")
    }

    @Test
    fun `INV-AC-26560 case 4 a document without calendars is refused`() {
        refused("{}", "calendars")
    }

    @Test
    fun `INV-AC-26560 case 5 empty entries are refused`() {
        refused(document(calendar(entries = "[]")), "calendars[0].entries")
    }

    @Test
    fun `INV-AC-26560 case 6 an acceptedFrom in another date form is refused`() {
        val entry = """{"version": "SYNTH-A", "acceptedFrom": "31/12/2000"}"""

        refused(document(calendar(entries = "[$entry]")), "calendars[0].entries[0].acceptedFrom")
    }

    @Test
    fun `INV-AC-26560 case 7 an empty version is refused`() {
        val entry = """{"version": "", "acceptedFrom": "2000-01-01"}"""

        val thrown = refused(document(calendar(entries = "[$entry]")), "calendars[0].entries[0].version")

        assertTrue(thrown.cause is IllegalArgumentException)
    }

    @Test
    fun `INV-AC-26560 case 8 a release order that holds one code twice is refused`() {
        val text = document(calendar(releaseOrder = """["SYNTH-A", "SYNTH-B", "SYNTH-A"]"""))

        val thrown = refused(text, "calendars[0].releaseOrder")

        assertTrue(thrown.cause is IllegalArgumentException)
    }

    @Test
    fun `INV-AC-26560 case 9 an unknown member is refused at the document, calendar and entry level`() {
        val atDocument = """{"calendars": [${calendar()}], "note": "x"}"""
        val inCalendar = document(calendar().dropLast(1) + """, "note": "x"}""")
        val inEntry = document(calendar(entries = "[${synthA.dropLast(1)}, \"note\": \"x\"}, $synthB]"))
        val lookAlike = """{"calendars": [${calendar()}], "Calendars": [${calendar()}]}"""

        refused(atDocument, "document", "note")
        refused(inCalendar, "calendars[0]:", "note")
        refused(inEntry, "calendars[0].entries[0]:", "note")
        refused(lookAlike, "document", "Calendars")
    }

    @Test
    fun `INV-AC-26560 case 10 an acceptedTo that is a number is refused`() {
        val entry = """{"version": "SYNTH-A", "acceptedFrom": "2000-01-01", "acceptedTo": 5}"""

        refused(document(calendar(entries = "[$entry]")), "calendars[0].entries[0].acceptedTo")
    }

    @Test
    fun `INV-AC-26560 case 11 an entry version missing from the release order is refused`() {
        val text = document(calendar(releaseOrder = """["SYNTH-A"]"""))

        val thrown = refused(text, "calendars[0].entries", "SYNTH-B")

        assertTrue(thrown.cause is IllegalArgumentException)
    }

    @Test
    fun `INV-AC-26560 case 12 an acceptedTo before acceptedFrom is refused`() {
        val entry = """{"version": "SYNTH-A", "acceptedFrom": "2000-06-30", "acceptedTo": "2000-06-29"}"""

        val thrown = refused(document(calendar(entries = "[$entry]")), "calendars[0].entries[0].acceptedTo")

        assertTrue(thrown.cause is IllegalArgumentException)
    }

    @Test
    fun `INV-AC-26560 case 13 a family that is not exactly a family name is refused`() {
        listOf("pint_sg", "UNKNOWN").forEach {
            refused(document(calendar(family = it)), "calendars[0].family", it)
        }
    }

    @Test
    fun `INV-AC-26560 case 14 a family named twice is refused`() {
        refused(document(calendar(), calendar()), "calendars[1].family", "PINT_SG")
    }
}
