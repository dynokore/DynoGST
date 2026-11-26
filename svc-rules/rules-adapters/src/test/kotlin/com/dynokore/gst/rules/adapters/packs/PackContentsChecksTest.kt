package com.dynokore.gst.rules.adapters.packs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * INV-T-6567: the checks of calendar and scenario contents that JSON Schema cannot express (INV-Q-6554, INV-Q-6556;
 * INV-REQ-774, INV-REQ-1117, INV-REQ-1279). All values are invented.
 */
class PackContentsChecksTest {
    private val checks = PackContentsChecks()

    private fun src(
        section: String,
        key: String = "",
    ) = """{"section":"$section",$key"sourceClass":"PRIMARY","sourceReference":"para 1"}"""

    private val holidays = listOf("2027-01-01", "2027-02-06", "2027-04-02", "2027-05-01", "2027-12-25")

    /** Two frequencies, five holidays, a row for every entry. */
    private fun calendar(
        windows: String =
            """{"MONTHLY":[{"start":"2027-01-01","end":"2027-01-31","returnForm":"F5"}],""" +
                """"QUARTERLY":[{"start":"2027-01-01","end":"2027-03-31","returnForm":"F5"}]}""",
        thresholds: String = """{"MONTHLY":[14,7,1],"QUARTERLY":[30,14,7]}""",
        margin: String? = """{"value":2,"unit":"DAYS"}""",
        holidayList: List<String> = holidays,
        rows: List<String> = allRows(),
    ): ByteArray {
        val quoted = holidayList.joinToString(",") { "\"$it\"" }
        return (
            """{"windows":$windows,"finalReturnForm":"F8","dueRules":{"F5":{"monthsAfterEnd":1,"endOfMonth":true,""" +
                """"daysAfterEnd":0}},"weekend":["SATURDAY","SUNDAY"],"holidays":[$quoted],""" +
                """"pinnedRule":"NEXT_WORKING_DAY","alertThresholds":$thresholds""" +
                margin?.let { ""","safetyMargin":$it""" }.orEmpty() +
                ""","entrySources":[${rows.joinToString(",")}]}"""
        ).toByteArray()
    }

    private fun refusal(
        contents: ByteArray,
        type: String = "CALENDAR",
    ) = checks.refusal(type, contents)

    private fun rowsWithout(marker: String) = allRows().filterNot { it.contains(marker) }

    private fun allRows() =
        listOf(
            src("windows", """"frequency":"MONTHLY","date":"2027-01-01","""),
            src("windows", """"frequency":"QUARTERLY","date":"2027-01-01","""),
            src("finalReturnForm"),
            src("dueRules", """"returnForm":"F5","""),
            src("weekend"),
            src("pinnedRule"),
            src("alertThresholds", """"frequency":"MONTHLY","""),
            src("alertThresholds", """"frequency":"QUARTERLY","""),
            src("safetyMargin"),
        ) + holidays.map { src("holidays", """"date":"$it",""") }

    @Test
    fun `INV-AC-1727 a complete calendar with two frequencies, five holidays and every row is accepted`() {
        refusal(calendar()) shouldBe null
    }

    @Test
    fun `INV-AC-1727 a window frequency without alertThresholds is refused naming it`() {
        refusal(calendar(thresholds = """{"MONTHLY":[14,7,1]}""")) shouldContain "windows.QUARTERLY"
    }

    @Test
    fun `INV-AC-1727 an alertThresholds key that is not a window frequency is refused naming it`() {
        refusal(calendar(thresholds = """{"MONTHLY":[14,7,1],"QUARTERLY":[30,14],"WEEKLY":[3,1]}""")) shouldContain
            "alertThresholds.WEEKLY"
    }

    @Test
    fun `INV-AC-1727 a threshold list that is not strictly descending is refused naming its frequency`() {
        refusal(calendar(thresholds = """{"MONTHLY":[14,7,1],"QUARTERLY":[30,7,7]}""")) shouldContain
            "alertThresholds.QUARTERLY is not strictly descending"
        refusal(calendar(thresholds = """{"MONTHLY":[1,7],"QUARTERLY":[30,14,7]}""")) shouldContain
            "alertThresholds.MONTHLY is not strictly descending"
    }

    @Test
    fun `INV-AC-1727 an absent safetyMargin is refused and nothing is defaulted`() {
        refusal(calendar(margin = null)) shouldBe "safetyMargin is absent"
    }

    @Test
    fun `INV-AC-1727 an entry without a row is refused naming the entry`() {
        refusal(calendar(rows = rowsWithout("\"section\":\"weekend\""))) shouldContain "no row for entry weekend"
        refusal(calendar(rows = rowsWithout("\"section\":\"safetyMargin\""))) shouldContain "entry safetyMargin"
        refusal(calendar(rows = rowsWithout("\"returnForm\":\"F5\""))) shouldContain "dueRules returnForm=F5"
    }

    @Test
    fun `INV-AC-1727 a pack without entrySources is refused at its first entry`() {
        val noRows = String(calendar()).replace(Regex(",\"entrySources\":.*\\}$"), "}")
        refusal(noRows.toByteArray()) shouldContain "no row for entry windows frequency=MONTHLY date=2027-01-01"
    }

    @Test
    fun `INV-AC-1727 two rows for one holiday are refused naming the date`() {
        val rows = allRows() + src("holidays", """"date":"2027-02-06",""")
        refusal(calendar(rows = rows)) shouldContain "2 rows for entry holidays date=2027-02-06"
    }

    @Test
    fun `INV-AC-1727 a row naming an entry the contents do not hold is refused`() {
        val rows = allRows() + src("holidays", """"date":"2027-03-03",""")
        refusal(calendar(rows = rows)) shouldContain "names an entry the contents do not hold: holidays date=2027-03-03"
    }

    @Test
    fun `INV-AC-1727 a holiday row dated one day off leaves the holiday without a row`() {
        val rows = allRows().map { it.replace("2027-02-06", "2027-02-07") }
        refusal(calendar(rows = rows)) shouldContain "no row for entry holidays date=2027-02-06"
    }

    @Test
    fun `INV-AC-1727 a window row dated at the window end leaves the window start without a row`() {
        val start = """"frequency":"MONTHLY","date":"2027-01-01""""
        val end = """"frequency":"MONTHLY","date":"2027-01-31""""
        val rows = allRows().map { it.replace(start, end) }
        refusal(calendar(rows = rows)) shouldContain "no row for entry windows frequency=MONTHLY date=2027-01-01"
    }

    @Test
    fun `INV-AC-1727 a row without a source class is refused and never read as PRIMARY`() {
        val rows = allRows().map { it.replace("\"sourceClass\":\"PRIMARY\",", "") }
        refusal(calendar(rows = rows)) shouldContain "entrySources[0].sourceClass is required"
    }

    @Test
    fun `INV-AC-1727 the pack type is compared by equality so look-alikes are not checked as calendar`() {
        val bad = calendar(margin = null)
        listOf("Calendar", "calendar", "CALENDAR-PACK", "calendar-pack").forEach { refusal(bad, it) shouldBe null }
        refusal(bad, "CALENDAR") shouldBe "safetyMargin is absent"
    }

    private fun party(
        direction: String,
        type: String,
    ) = """{"direction":"$direction","submissionType":"$type","sourceClass":"INFERRED","sourceReference":"s"}"""

    private fun treatment(member: String = "") =
        """{"tag":"ZZTEST","side":"SUPPLY","categoryCode":"NA","rateBasis":"FIXED","rate":"0",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s"$member}"""

    /** The members of the four sections the handlers schema requires, without the surrounding braces. */
    private fun handlerSections(treatments: String = "[${treatment()}]") =
        """"selfBillingAssignment":{"customerCumIssuer":${party("PURCHASE", "TYPE_3")},""" +
            """"supplierCumRecipient":${party("SALE", "TYPE_2")}},""" +
            """"schemeTagSides":[{"tag":"ZZTEST","side":"SUPPLY","handling":"HANDLED",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s"}],""" +
            """"treatments":$treatments,""" +
            """"approvableSchemes":[{"scheme":"ZZTEST","sourceClass":"PRIMARY","sourceReference":"s"}]"""

    private fun percentContents(member: String) = "{${handlerSections("[${treatment(member)}]")}}".toByteArray()

    private fun decodeMessage(contents: ByteArray) =
        shouldThrow<PackContentsFailure> { ScenarioPackContents.decode(contents) }.message

    @Test
    fun `INV-AC-26687 complete scenario contents pass with gstBasePercent 50 and with none`() {
        refusal(percentContents(""","gstBasePercent":"50""""), "SCENARIO") shouldBe null
        refusal(percentContents(""), "SCENARIO") shouldBe null
    }

    @Test
    fun `INV-AC-26687 a gstBasePercent of 100 point 5 is refused with the decoder's message`() =
        refusedAs(""""100.5"""")

    @Test
    fun `INV-AC-26687 an invalid gstBasePercent is refused with the decoder's message`() {
        listOf(""""101"""", """"50%"""", "50", "null").forEach(::refusedAs)
    }

    private fun refusedAs(value: String) {
        val contents = percentContents(""","gstBasePercent":$value""")
        val answer = refusal(contents, "SCENARIO")
        answer shouldBe decodeMessage(contents)
        answer shouldContain "$.treatments[0].gstBasePercent"
    }

    @Test
    fun `INV-AC-26687 contents that cannot be decoded are refused and nothing is thrown`() {
        listOf(
            "not json",
            "{",
            "{${handlerSections(""""none"""")}}",
            "{${handlerSections("""{"a":${treatment()}}""")}}",
        ).forEach {
            val contents = it.toByteArray()
            val answer = refusal(contents, "SCENARIO")
            answer shouldNotBe null
            answer shouldBe decodeMessage(contents)
        }
    }

    @Test
    fun `INV-AC-26687 the pack type Scenario is not checked as the scenario pack type`() {
        refusal(percentContents(""","gstBasePercent":"100.5""""), "Scenario") shouldBe null
    }

    private fun scenario(vararg items: String) =
        (
            """{"renditionWording":[{"scenario":"S","submissionType":"TYPE_2",""" +
                """"requiredWording":[${items.joinToString(",")}]}],${handlerSections()}}"""
        ).toByteArray()

    @Test
    fun `INV-AC-1727 a scenario item without sourceClass refuses the pack while a classified item passes`() {
        val classified = """{"text":"Credit Note","citation":"c","sourceClass":"PRIMARY"}"""
        val unclassified = """{"text":"Debit Note","citation":"c"}"""
        refusal(scenario(classified), "SCENARIO") shouldBe null
        refusal(scenario(classified, unclassified), "SCENARIO") shouldBe
            "renditionWording[0].requiredWording[1].sourceClass is required"
        refusal(scenario(classified.replace("PRIMARY", "primary")), "SCENARIO") shouldContain "sourceClass"
    }

    @Test
    fun `INV-AC-1727 a scenario pack without renditionWording passes this check`() {
        refusal("{${handlerSections()}}".toByteArray(), "SCENARIO") shouldBe null
    }

    @Test
    fun `INV-AC-1727 an alertThresholds member that is not an object is refused naming it and nothing is thrown`() {
        refusal(calendar(thresholds = "[14,7,1]")) shouldBe "alertThresholds must be an object"
        refusal(calendar(thresholds = """{"MONTHLY":"14","QUARTERLY":[30,14,7]}""")) shouldBe
            "alertThresholds.MONTHLY must be a list of integers"
        refusal(calendar(rows = listOf("7"))) shouldContain "entrySources"
    }
}
