package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonAggregateWindowChangedDataReader
import com.dynokore.gst.submission.application.completeness.WindowChangeState
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The MISSED state and the `spanKnown` member in the AggregateWindowChanged reader (INV-AC-27723). */
class MissedWindowReaderTest {
    private val reader = JacksonAggregateWindowChangedDataReader()

    private fun json(
        state: String = "MISSED",
        period: String = "P1",
        extra: String = "",
    ) = "{\"entityRef\":\"E1\",\"prescribedPeriod\":\"$period\"," +
        "\"window\":{\"from\":\"2026-05-01\",\"to\":\"2026-05-07\"},\"state\":\"$state\",\"stateVersion\":3$extra}"

    @Test
    fun `INV-AC-27723 MISSED is read with a known span when spanKnown is absent or true`() {
        listOf("", ",\"spanKnown\":true").forEach {
            val d = reader.read(json(extra = it))
            d.state shouldBe WindowChangeState.MISSED
            d.spanKnown shouldBe true
        }
    }

    @Test
    fun `INV-AC-27723 findingCode is not read`() {
        val expected = reader.read(json())
        listOf("\"ING-027\"", "\"X-999\"", "7", "null").forEach {
            reader.read(json(extra = ",\"findingCode\":$it")) shouldBe expected
        }
    }

    @Test
    fun `INV-AC-27723 case 6 a state that differs from MISSED by case or space is a ContractViolation`() {
        listOf("missed", "Missed", "MISSED ", " MISSED").forEach {
            withClue("state '$it'") {
                val e = shouldThrow<ContractViolation> { reader.read(json(state = it)) }
                e.message.orEmpty() shouldNotContain it
            }
        }
    }

    @Test
    fun `INV-AC-27723 case 9 spanKnown false is read with MISSED and UNKNOWN-SPAN`() {
        val d = reader.read(json(period = "UNKNOWN-SPAN", extra = ",\"spanKnown\":false"))
        d.spanKnown shouldBe false
        d.prescribedPeriod shouldBe "UNKNOWN-SPAN"
        d.state shouldBe WindowChangeState.MISSED
    }

    @Test
    fun `INV-AC-27723 case 10 spanKnown false with another state or period is a ContractViolation`() {
        val bad =
            listOf("OPEN", "CLOSED", "LATE").map { it to "UNKNOWN-SPAN" } +
                listOf("2026Q1", "unknown-span", "UNKNOWN-SPAN ", "UNKNOWN-SPAN2").map { "MISSED" to it }
        bad.forEach { (state, period) ->
            withClue("$state '$period'") {
                shouldThrow<ContractViolation> { reader.read(json(state, period, ",\"spanKnown\":false")) }
            }
        }
    }

    @Test
    fun `INV-AC-27723 case 10 a spanKnown that is not a JSON boolean is a ContractViolation`() {
        listOf("\"false\"", "\"true\"", "0", "1", "null", "[false]", "{}").forEach {
            withClue("spanKnown $it") {
                shouldThrow<ContractViolation> {
                    reader.read(
                        json(period = "UNKNOWN-SPAN", extra = ",\"spanKnown\":$it"),
                    )
                }
            }
        }
    }
}
