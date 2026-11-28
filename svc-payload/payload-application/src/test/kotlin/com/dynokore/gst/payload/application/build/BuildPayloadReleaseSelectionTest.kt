package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.gst.payload.application.payload.RegenerationFacts
import com.dynokore.gst.payload.domain.SpecificationFamily
import com.dynokore.platform.integrationcanon.AcceptanceCalendar
import com.dynokore.platform.integrationcanon.AcceptanceEntry
import com.dynokore.platform.integrationcanon.ReleaseOrder
import com.dynokore.platform.integrationcanon.SpecVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** A selector that selects a synthetic release for every family and any date, for tests of the other build steps. */
internal fun acceptingSelector(): SpecificationReleaseSelector {
    val release = SpecVersion("SYNTH-A")
    val calendar =
        AcceptanceCalendar.of(
            ReleaseOrder.of(listOf(release)),
            listOf(AcceptanceEntry(release, LocalDate.parse("2000-01-01"), null)),
        )
    return SpecificationReleaseSelector({ calendar }, { LocalDate.parse("2000-07-15") })
}

class BuildPayloadReleaseSelectionTest {
    private val doc = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val small = "<Invoice/>".toByteArray()
    private val big = ByteArray(10_485_761)
    private val transactionDate = LocalDate.parse("2000-02-01")
    private val submissionDate = LocalDate.parse("2000-07-15")

    private val synthA = SpecVersion("SYNTH-A")
    private val synthB = SpecVersion("SYNTH-B")
    private val order = ReleaseOrder.of(listOf(synthA, synthB))
    private val entryA = AcceptanceEntry(synthA, LocalDate.parse("2000-01-01"), LocalDate.parse("2000-06-30"))
    private val entryB = AcceptanceEntry(synthB, LocalDate.parse("2000-03-01"), null)

    /** On the submission date only SYNTH-B is accepted: selects SYNTH-B. */
    private val selectsB = AcceptanceCalendar.of(order, listOf(entryA, entryB))

    /** On the submission date nothing is accepted: refuses with NO_ACCEPTED_RELEASE. */
    private val refuses = AcceptanceCalendar.of(order, listOf(entryA))

    private val ready = ArrayList<PayloadReadyFacts>()
    private val rejected = ArrayList<PayloadRejectedFacts>()

    /** The families the selector asked a calendar for, and the number of build dates it read: one each per select. */
    private val askedFamilies = ArrayList<SpecificationFamily>()
    private var buildDateReads = 0

    private val refusal =
        NoAcceptedReleaseFacts(
            ReleaseRefusalReason.NO_ACCEPTED_RELEASE,
            "PINT_SG",
            transactionDate,
            submissionDate,
            "SYNTH-A",
            emptyList(),
        )

    private fun build(
        projection: Projection,
        calendar: AcceptanceCalendar = selectsB,
        family: String = "PINT_SG",
        inputDate: LocalDate? = transactionDate,
    ) = BuildPayload(
        inputs = { _, _ -> BuildInput("ENT-1", "{}", "AGG-1", null, null, null, inputDate) },
        decisions = { _, _ -> "TYPE_2" },
        projector = { _, _ -> projection },
        families = { family },
        ready = PublishPayloadReady { ready += it },
        rejected = { rejected += it },
        outcomes = { _, _ -> true },
        layerOne = NOT_WIRED,
        releases =
            SpecificationReleaseSelector(
                {
                    askedFamilies += it
                    calendar
                },
                {
                    buildDateReads++
                    submissionDate
                },
            ),
    )

    @Test
    fun `INV-AC-26579 a selected release is named in the regeneration key of PayloadReady`() {
        build(Projection.Built(small, "MDE-7")).run(doc, 3)
        val facts = ready.single()
        facts.regeneration shouldBe RegenerationFacts(3, "SYNTH-B", listOf(PackVersionFact("MDE", "MDE-7")))
        facts.specificationVersion shouldBe "PINT_SG"
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a projection without a pack version publishes no regeneration key`() {
        build(Projection.Built(small)).run(doc, 1)
        ready.single().regeneration shouldBe null
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a refused selection is rejected with PAY-020 and the refusal facts`() {
        build(Projection.Built(small, "MDE-7"), refuses).run(doc, 1)
        rejected.single() shouldBe
            PayloadRejectedFacts(
                entityRef = "ENT-1",
                docUuid = doc,
                version = 1,
                code = "PAY-020",
                validationTrace = null,
                packVersions = listOf(PackVersionFact("MDE", "MDE-7")),
                releaseRefusal = refusal,
            )
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a refused selection without a pack version has no packVersions`() {
        build(Projection.Built(small), refuses).run(doc, 1)
        rejected.single().packVersions shouldBe null
        rejected.single().releaseRefusal shouldBe refusal
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a blocked projection is rejected as before and never selects`() {
        build(Projection.Blocked("{\"finding\":\"BLOCK\"}", "MDE-7"), refuses).run(doc, 1)
        rejected.single() shouldBe
            PayloadRejectedFacts(
                "ENT-1",
                doc,
                1,
                null,
                "{\"finding\":\"BLOCK\"}",
                listOf(PackVersionFact("MDE", "MDE-7")),
            )
        buildDateReads shouldBe 0
        askedFamilies shouldBe emptyList()
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a look-alike family text throws and stages nothing`() {
        listOf("pint_sg", "PINT-SG", "PINT_SG ", "SG_PEPPOL_BIS2", "").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                build(Projection.Built(small, "MDE-7"), family = text).run(doc, 1)
            }
        }
        buildDateReads shouldBe 0
        ready.size shouldBe 0
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a built projection selects exactly once for its family and the input transaction date`() {
        build(Projection.Built(small, "MDE-7"), refuses, family = "SG_PEPPOL_BIS").run(doc, 1)
        buildDateReads shouldBe 1
        askedFamilies shouldBe listOf(SpecificationFamily.SG_PEPPOL_BIS)
        rejected.single().releaseRefusal!!.transactionDate shouldBe transactionDate

        buildDateReads = 0
        askedFamilies.clear()
        build(Projection.Built(small, "MDE-7")).run(doc, 2)
        buildDateReads shouldBe 1
        askedFamilies shouldBe listOf(SpecificationFamily.PINT_SG)
    }

    @Test
    fun `INV-AC-26579 an input without a transaction date is refused`() {
        build(Projection.Built(small, "MDE-7"), inputDate = null).run(doc, 1)
        rejected.single().releaseRefusal!!.reason shouldBe ReleaseRefusalReason.NO_TRANSACTION_DATE
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a payload over the limit is rejected with PAY-010 after a selected release`() {
        build(Projection.Built(big, "MDE-7")).run(doc, 1)
        rejected.single().code shouldBe "PAY-010"
        rejected.single().releaseRefusal shouldBe null
        buildDateReads shouldBe 1
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-26579 a refused selection wins over a payload over the limit`() {
        build(Projection.Built(big, "MDE-7"), refuses).run(doc, 1)
        rejected.single().code shouldBe "PAY-020"
        ready.size shouldBe 0
    }
}
