package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.ConfiguredAcceptanceCalendars
import com.dynokore.gst.payload.application.build.AcceptanceCalendarSource
import com.dynokore.gst.payload.application.build.BuildDateSource
import com.dynokore.gst.payload.application.build.SpecificationReleaseSelector
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The beans of the release selection (INV-REQ-590). Both properties are installation configuration with no default,
 * read once at start-up; an invalid value stops the start.
 */
@Configuration(proxyBeanMethods = false)
class PayloadReleaseConfiguration {
    /** An absent or blank text gives no calendar, so every build is refused; a malformed one throws. */
    @Bean
    fun acceptanceCalendarSource(environment: Environment): AcceptanceCalendarSource =
        ConfiguredAcceptanceCalendars.parse(environment.getProperty(CALENDAR_PROPERTY))

    /** The date of the injected clock in the configured build time zone (INV-BR-215). */
    @Bean
    fun buildDateSource(
        environment: Environment,
        clock: Clock,
    ): BuildDateSource {
        val zone = zoneOf(environment.getProperty(TIME_ZONE_PROPERTY))
        return BuildDateSource { LocalDate.now(clock.withZone(zone)) }
    }

    @Bean
    fun specificationReleaseSelector(
        calendars: AcceptanceCalendarSource,
        buildDate: BuildDateSource,
    ): SpecificationReleaseSelector = SpecificationReleaseSelector(calendars, buildDate)

    private fun zoneOf(name: String?): ZoneId {
        check(!name.isNullOrBlank()) { "the property $TIME_ZONE_PROPERTY is required" }
        return try {
            ZoneId.of(name)
        } catch (e: DateTimeException) {
            throw IllegalStateException("the property $TIME_ZONE_PROPERTY is not a time zone: '$name'", e)
        }
    }

    private companion object {
        const val CALENDAR_PROPERTY = "payload.acceptance-calendar"
        const val TIME_ZONE_PROPERTY = "payload.build.time-zone"
    }
}
