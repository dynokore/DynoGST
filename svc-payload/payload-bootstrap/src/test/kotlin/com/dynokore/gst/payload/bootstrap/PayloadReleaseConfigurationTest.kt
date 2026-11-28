package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.AcceptanceCalendarInvalid
import com.dynokore.gst.payload.adapters.build.ConfiguredAcceptanceCalendars
import com.dynokore.gst.payload.application.build.AcceptanceCalendarSource
import com.dynokore.gst.payload.application.build.BuildDateSource
import com.dynokore.gst.payload.application.build.ReleaseSelection
import com.dynokore.gst.payload.application.build.SpecificationReleaseSelector
import com.dynokore.gst.payload.domain.SpecificationFamily
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The release-selection beans in the service composed as TPL-18 writes it (P109). No database is opened. */
class PayloadReleaseConfigurationTest {
    // The calendar types of integration-canon are on the runtime classpath of this module only, so the two calls
    // that return them go through reflection; a renamed method fails the test.
    private fun calendarOf(
        source: AcceptanceCalendarSource,
        family: SpecificationFamily,
    ): Any? =
        AcceptanceCalendarSource::class.java
            .getMethod("calendarOf", SpecificationFamily::class.java)
            .invoke(source, family)

    private fun acceptedOn(
        calendar: Any,
        date: LocalDate,
    ): List<String> =
        (calendar.javaClass.getMethod("acceptedOn", LocalDate::class.java).invoke(calendar, date) as List<*>)
            .map { it.toString() }

    @Test
    fun `INV-AC-26579 the service does not start without a usable build time zone`(
        @TempDir dir: Path,
    ) {
        listOf(null, "", "   ", "Not/AZone").forEach { zone ->
            PayloadContext.offline(dir, timeZone = zone).run { context ->
                context.startupFailure.shouldNotBeNull().message!! shouldContain "payload.build.time-zone"
            }
        }
    }

    @Test
    fun `INV-AC-26579 the service does not start with a malformed acceptance calendar`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offline(dir, calendar = """{"calendars":[],"extra":1}""").run { context ->
            val causes = generateSequence(context.startupFailure.shouldNotBeNull()) { it.cause }.toList()
            val invalid = causes.filterIsInstance<AcceptanceCalendarInvalid>().single()
            invalid.message!! shouldContain "extra"
        }
    }

    @Test
    fun `INV-AC-26579 without the calendar property the service starts and no family has a calendar`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offline(dir, calendar = null).run { context ->
            context.startupFailure shouldBe null
            context.environment.getProperty("payload.acceptance-calendar") shouldBe null
            val source = context.getBean(AcceptanceCalendarSource::class.java)
            SpecificationFamily.entries.forEach { calendarOf(source, it) shouldBe null }
        }
    }

    @Test
    fun `INV-AC-26579 the build date is the date of the injected clock in the configured zone`(
        @TempDir dir: Path,
    ) {
        val clock = Clock.fixed(Instant.parse("2000-07-14T23:30:00Z"), ZoneOffset.UTC)
        mapOf("Asia/Tokyo" to "2000-07-15", "UTC" to "2000-07-14").forEach { (zone, date) ->
            PayloadContext
                .offline(dir, timeZone = zone, calendar = null)
                .withBean("fixedClock", Clock::class.java, { clock }, { it.isPrimary = true })
                .run { context ->
                    context.getBean(BuildDateSource::class.java).today() shouldBe LocalDate.parse(date)
                    // the selector takes its submission date from that port, never from the transaction date
                    val selection =
                        context
                            .getBean(SpecificationReleaseSelector::class.java)
                            .select(SpecificationFamily.PINT_SG, LocalDate.parse("2000-02-01"))
                    (selection as ReleaseSelection.Refused).facts.submissionDate shouldBe LocalDate.parse(date)
                }
        }
    }

    @Test
    fun `INV-AC-26579 the synthetic calendar of the test context covers every specification family`(
        @TempDir dir: Path,
    ) {
        PayloadContext.offline(dir).run { context ->
            val text = context.environment.getProperty("payload.acceptance-calendar").shouldNotBeNull()
            val calendars = ConfiguredAcceptanceCalendars.parse(text)
            SpecificationFamily.entries.forEach { family ->
                val calendar = calendarOf(calendars, family).shouldNotBeNull()
                listOf("2026-03-19", "2026-03-20").forEach {
                    acceptedOn(calendar, LocalDate.parse(it)) shouldBe listOf("SYNTH-A")
                }
            }
        }
    }
}
