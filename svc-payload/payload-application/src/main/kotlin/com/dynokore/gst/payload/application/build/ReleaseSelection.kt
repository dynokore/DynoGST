package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.domain.ReleaseChoice
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.gst.payload.domain.SubmissionRelease
import com.dynokore.gst.payload.domain.SubmissionReleaseChoice
import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.SpecVersion
import java.time.LocalDate

/** The acceptance calendar of a family (key payload.acceptance-calendar); null means no calendar for the family. */
fun interface AcceptanceCalendarSource {
    fun calendarOf(family: SpecificationFamily): AcceptanceCalendar?
}

/** The clock port: the build date in the time zone of the key payload.build.time-zone (INV-BR-215). */
fun interface BuildDateSource {
    fun today(): LocalDate
}

/** Why a build has no specification release. */
enum class ReleaseRefusalReason {
    NO_CALENDAR,
    NO_TRANSACTION_DATE,
    NO_RELEASE_ON_TRANSACTION_DATE,
    NO_ACCEPTED_RELEASE,
}

/** The facts of a refusal; [notOlderThan] and [acceptedOnDate] are release codes as text. */
data class NoAcceptedReleaseFacts(
    val reason: ReleaseRefusalReason,
    val family: String,
    val transactionDate: LocalDate?,
    val submissionDate: LocalDate,
    val notOlderThan: String?,
    val acceptedOnDate: List<String>,
)

sealed interface ReleaseSelection {
    data class Selected(
        val release: SubmissionRelease,
    ) : ReleaseSelection

    data class Refused(
        val facts: NoAcceptedReleaseFacts,
    ) : ReleaseSelection
}

/** The catalogue code of a refused release selection. */
object ReleaseRefusal {
    const val CODE = "PAY-020"
}

/**
 * Selects the specification release of a build from the acceptance calendar, the transaction date and the build
 * date (INV-REQ-590). Pure: both inputs arrive through ports, nothing is defaulted, every gap is a refusal.
 */
class SpecificationReleaseSelector(
    private val calendars: AcceptanceCalendarSource,
    private val buildDate: BuildDateSource,
) {
    fun select(
        family: SpecificationFamily,
        transactionDate: LocalDate?,
    ): ReleaseSelection {
        val submissionDate = buildDate.today()
        val calendar = calendars.calendarOf(family)
        val transactionRelease =
            if (calendar != null &&
                transactionDate != null
            ) {
                calendar.acceptedOn(transactionDate).firstOrNull()
            } else {
                null
            }
        return when {
            calendar == null -> {
                refused(family, transactionDate, submissionDate, ReleaseRefusalReason.NO_CALENDAR)
            }

            transactionDate == null -> {
                refused(family, null, submissionDate, ReleaseRefusalReason.NO_TRANSACTION_DATE)
            }

            transactionRelease == null -> {
                refused(family, transactionDate, submissionDate, ReleaseRefusalReason.NO_RELEASE_ON_TRANSACTION_DATE)
            }

            else -> {
                choose(family, calendar, transactionRelease, transactionDate, submissionDate)
            }
        }
    }

    private fun choose(
        family: SpecificationFamily,
        calendar: AcceptanceCalendar,
        transactionRelease: SpecVersion,
        transactionDate: LocalDate?,
        submissionDate: LocalDate,
    ): ReleaseSelection =
        when (val choice = SubmissionReleaseChoice.choose(family, calendar, transactionRelease, submissionDate)) {
            is ReleaseChoice.Chosen -> {
                ReleaseSelection.Selected(choice.submission)
            }

            is ReleaseChoice.NoAcceptedRelease -> {
                val bare = refused(family, transactionDate, submissionDate, ReleaseRefusalReason.NO_ACCEPTED_RELEASE)
                ReleaseSelection.Refused(
                    bare.facts.copy(
                        notOlderThan = choice.notOlderThan.code,
                        acceptedOnDate = choice.acceptedOnDate.map { it.code },
                    ),
                )
            }
        }

    private fun refused(
        family: SpecificationFamily,
        transactionDate: LocalDate?,
        submissionDate: LocalDate,
        reason: ReleaseRefusalReason,
    ) = ReleaseSelection.Refused(
        NoAcceptedReleaseFacts(reason, family.name, transactionDate, submissionDate, null, emptyList()),
    )
}
