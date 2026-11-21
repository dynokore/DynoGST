package com.dynokore.gst.submission.application.readiness

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RelaxedControlsOutcomeTest {
    private val day = LocalDate.parse("2026-11-12")
    private val t1 = Instant.parse("2026-11-01T08:00:00Z")
    private val t2 = Instant.parse("2026-11-02T09:30:00Z")

    private fun entry(
        vp: String = "VP-14",
        opt: String = "loose",
        by: String = "u-1",
        at: Instant = t1,
    ) = RelaxedControlEntry(vp, opt, by, at)

    private class Fake(
        private val answer: List<RelaxedControlEntry>?,
    ) : RelaxedControlSource {
        val calls = mutableListOf<Pair<String, LocalDate>>()

        override fun entriesOn(
            entityRef: String,
            on: LocalDate,
        ): List<RelaxedControlEntry>? {
            calls += entityRef to on
            return answer
        }
    }

    private fun render(vararg e: RelaxedControlEntry) = RelaxedControlsOutcome(Fake(e.toList())).json("ent-1", day)

    private fun obj(
        vp: String,
        opt: String,
        by: String,
        at: String,
    ) = """{"variationPoint":"$vp","optionKey":"$opt","acknowledgedBy":"$by","acknowledgedAt":"$at"}"""

    private fun arr(vararg o: String) = o.joinToString(",", "[", "]")

    @Test
    fun `INV-AC-26644 R1 entries come out sorted by variationPoint then acknowledgedAt`() {
        render(entry("VP-20", at = t1), entry("VP-14", at = t1)) shouldBe
            arr(
                obj("VP-14", "loose", "u-1", "2026-11-01T08:00:00Z"),
                obj("VP-20", "loose", "u-1", "2026-11-01T08:00:00Z"),
            )
        render(entry(at = t2), entry(at = t1)) shouldBe
            arr(
                obj("VP-14", "loose", "u-1", "2026-11-01T08:00:00Z"),
                obj("VP-14", "loose", "u-1", "2026-11-02T09:30:00Z"),
            )
    }

    @Test
    fun `INV-AC-26644 R1 same variationPoint and instant are ordered by acknowledgedBy`() {
        render(entry(by = "u-2"), entry(by = "u-1")) shouldBe
            arr(
                obj("VP-14", "loose", "u-1", "2026-11-01T08:00:00Z"),
                obj("VP-14", "loose", "u-2", "2026-11-01T08:00:00Z"),
            )
    }

    @Test
    fun `INV-AC-26644 R2 each object has exactly the four members with the instant text`() {
        render(entry(at = Instant.parse("2026-11-01T08:00:00.500Z"))) shouldBe
            arr(
                obj("VP-14", "loose", "u-1", "2026-11-01T08:00:00.500Z"),
            )
    }

    @Test
    fun `INV-AC-26644 R3 no entry gives an empty array`() {
        render() shouldBe "[]"
    }

    @Test
    fun `INV-AC-26644 R4 a null source answer is refused and not rendered as empty`() {
        assertThrows(RelaxedControlsUnreadable::class.java) { RelaxedControlsOutcome(Fake(null)).json("ent-1", day) }
    }

    @Test
    fun `INV-AC-26644 R5 blank fields and malformed variation points are refused`() {
        listOf(
            entry(opt = " "),
            entry(by = ""),
            entry(vp = "VP-"),
            entry(vp = "vp-14"),
            entry(vp = "XVP-14"),
            entry(vp = "VP-14x"),
        ).forEach { e ->
            assertThrows(RelaxedControlsUnreadable::class.java) { render(e) }
        }
    }

    @Test
    fun `INV-AC-26644 R5 a blank entityRef is an illegal argument`() {
        assertThrows(IllegalArgumentException::class.java) { RelaxedControlsOutcome(Fake(emptyList())).json(" ", day) }
    }

    @Test
    fun `INV-AC-26644 R6 quote backslash and control character are escaped and parse back`() {
        val raw = "a\"b\\c\u0001d"
        val json = render(entry(by = raw))
        json shouldBe
            arr(
                obj("VP-14", "loose", "a\\\"b\\\\c\\u0001d", "2026-11-01T08:00:00Z"),
            )
        val inner = json.substringAfter("\"acknowledgedBy\":\"").substringBefore("\",\"acknowledgedAt\"")
        unescape(inner) shouldBe raw
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
            } else if (s[i + 1] == 'u') {
                out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                out.append(s[i + 1])
                i += 2
            }
        }
        return out.toString()
    }

    @Test
    fun `INV-AC-26644 R7 the source is asked once with the entityRef and date passed in`() {
        val fake = Fake(listOf(entry()))
        RelaxedControlsOutcome(fake).json("ent-9", day)
        fake.calls shouldBe listOf("ent-9" to day)
    }

    @Test
    fun `INV-AC-26644 R8 exactly 200 entries render and 201 are refused`() {
        fun many(n: Int) = (1..n).map { entry(vp = "VP-$it") }
        RelaxedControlsOutcome(Fake(many(200))).json("ent-1", day).split("{\"variationPoint\"").size shouldBe 201
        assertThrows(
            RelaxedControlsUnreadable::class.java,
        ) { RelaxedControlsOutcome(Fake(many(201))).json("ent-1", day) }
    }
}
