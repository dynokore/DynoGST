package com.dynokore.gst.submission.application.readiness

import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** One relaxed control: the variation point, the chosen option, and who acknowledged it and when. */
data class RelaxedControlEntry(
    val variationPoint: String,
    val optionKey: String,
    val acknowledgedBy: String,
    val acknowledgedAt: Instant,
)

/** Port: the relaxed-control entries in force for an entity on a date; null means they cannot be read. */
fun interface RelaxedControlSource {
    fun entriesOn(
        entityRef: String,
        on: LocalDate,
    ): List<RelaxedControlEntry>?
}

class RelaxedControlsUnreadable(
    message: String,
) : RuntimeException(message)

/** Renders the `relaxedControls` array of the readiness outcome values; fails closed on unreadable input. */
class RelaxedControlsOutcome(
    private val source: RelaxedControlSource,
) {
    fun json(
        entityRef: String,
        on: LocalDate,
    ): String {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        val entries =
            source.entriesOn(entityRef, on) ?: throw RelaxedControlsUnreadable("relaxed controls cannot be read")
        if (entries.size > MAX_ENTRIES) throw RelaxedControlsUnreadable("more than $MAX_ENTRIES relaxed controls")
        entries.forEach { check(it) }
        val sorted =
            entries.sortedWith(
                compareBy<RelaxedControlEntry>({ it.variationPoint }, { it.acknowledgedAt }, { it.acknowledgedBy }),
            )
        return sorted.joinToString(",", "[", "]") { render(it) }
    }

    private fun render(e: RelaxedControlEntry): String {
        val vp = quoted(e.variationPoint)
        val opt = quoted(e.optionKey)
        val by = quoted(e.acknowledgedBy)
        val at = quoted(e.acknowledgedAt.toString())
        return """{"variationPoint":$vp,"optionKey":$opt,"acknowledgedBy":$by,"acknowledgedAt":$at}"""
    }

    private fun check(e: RelaxedControlEntry) {
        val problem =
            when {
                !VP.matches(e.variationPoint) -> "variationPoint does not match VP-<digits>"
                e.optionKey.isBlank() -> "blank optionKey"
                e.acknowledgedBy.isBlank() -> "blank acknowledgedBy"
                else -> null
            }
        if (problem != null) throw RelaxedControlsUnreadable(problem)
    }

    private fun quoted(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(Locale.ROOT, c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private companion object {
        const val MAX_ENTRIES = 200
        val VP = Regex("VP-[0-9]+")
    }
}
