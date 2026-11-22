package com.dynokore.gst.consoleBff.application.privacy

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private val RECORDED = Instant.parse("2026-11-20T08:00:00Z")

class CorrectPersonalDataTest {
    private val at = Instant.parse("2026-11-20T08:00:00Z")
    private val refusal = OwnerRefusal(422, "MDM-100", "Refused", "BLOCK", "TERMINAL", "OWNER", "detail")

    private val base =
        CorrectPersonalDataCommand(
            "t1",
            "u1",
            "ENT-1",
            "CP-1",
            "ack-1",
            "case-1",
            "legalName",
            "New Name Pte Ltd",
            "client request",
        )

    private class Fixture(
        val grant: Boolean = true,
        val status: OwnerReply<String?> = OwnerReply.Ok("OPEN"),
        val correction: OwnerReply<CorrectedVersion> = OwnerReply.Ok(CorrectedVersion(RECORDED)),
        annotationReplies: List<OwnerReply<Unit>> = listOf(OwnerReply.Ok(Unit)),
    ) {
        val calls = mutableListOf<String>()
        val correctionKeys = mutableListOf<String>()
        val annotationKeys = mutableListOf<String>()
        val correctionArgs = mutableListOf<Pair<String, CorrectionRequest>>()
        val annotationArgs = mutableListOf<List<String>>()
        private val queue = annotationReplies.toMutableList()
        val useCase =
            CorrectPersonalData(
                { t, u, e ->
                    calls += "holds($t,$u,$e)"
                    grant
                },
                { c ->
                    calls += "status($c)"
                    status
                },
                { cp, key, req ->
                    calls += "correct"
                    correctionKeys += key
                    correctionArgs += cp to req
                    correction
                },
                { c, key, ack, f, r ->
                    calls += "annotate"
                    annotationKeys += key
                    annotationArgs += listOf(c, ack, f, r)
                    if (queue.size > 1) queue.removeAt(0) else queue[0]
                },
            )
    }

    private val corrected = CorrectPersonalDataOutcome.Corrected("case-1", "legalName", "New Name Pte Ltd", at)
    private val down = OwnerReply.Unavailable("down")
    private val refused = OwnerReply.Refused(refusal)

    @Test
    fun `INV-AC-27139 happy path calls case read, correction, annotation once in order`() {
        val f = Fixture()
        f.useCase.handle(base) shouldBe corrected
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)", "correct", "annotate")
        val request = CorrectionRequest("ENT-1", "case-1", "legalName", "New Name Pte Ltd", "client request")
        f.correctionArgs shouldBe listOf("CP-1" to request)
        f.annotationArgs shouldBe listOf(listOf("case-1", "ack-1", "legalName", "client request"))
    }

    @Test
    fun `INV-AC-27139 missing blank or whitespace identifiers are Invalid with no port call`() {
        for (bad in listOf(null, "", "   ")) {
            for (c in listOf(base.copy(entityRef = bad), base.copy(counterpartyRef = bad), base.copy(ackId = bad))) {
                val f = Fixture()
                f.useCase.handle(c) shouldBe CorrectPersonalDataOutcome.Invalid
                f.calls shouldBe emptyList()
            }
        }
    }

    @Test
    fun `INV-AC-27139 unsupported field and look-alikes are Invalid`() {
        for (field in listOf("counterpartyName", "LEGALNAME", "legalName ", "")) {
            val f = Fixture()
            f.useCase.handle(base.copy(field = field)) shouldBe CorrectPersonalDataOutcome.Invalid
            f.calls shouldBe emptyList()
        }
        for (field in listOf("legalName", "addressLine1", "addressLine2", "postalCode")) {
            val outcome = Fixture().useCase.handle(base.copy(field = field))
            (outcome is CorrectPersonalDataOutcome.Corrected) shouldBe true
        }
    }

    @Test
    fun `INV-AC-27139 no permission is Forbidden and no owner port is called`() {
        val f = Fixture(grant = false)
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Forbidden
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)")
    }

    @Test
    fun `INV-AC-27139 unknown case or non-OPEN status is NotFound and no write port is called`() {
        for (s in listOf(null, "CLOSED", "EXPORT_READY", "open", "")) {
            val f = Fixture(status = OwnerReply.Ok(s))
            f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.NotFound
            f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)")
        }
    }

    @Test
    fun `INV-AC-27139 owner refusal at case read is relayed and nothing later is called`() {
        val f = Fixture(status = refused)
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Relayed(refusal)
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)")
    }

    @Test
    fun `INV-AC-27139 owner refusal at correction is relayed and no annotation is called`() {
        val f = Fixture(correction = refused)
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Relayed(refusal)
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)", "correct")
    }

    @Test
    fun `INV-AC-27139 owner refusal at annotation is relayed at once without retry`() {
        val f = Fixture(annotationReplies = listOf(refused))
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Relayed(refusal)
        f.calls.count { it == "annotate" } shouldBe 1
    }

    @Test
    fun `INV-AC-27139 unavailable at case read is Unavailable`() {
        val f = Fixture(status = down)
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Unavailable
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)")
    }

    @Test
    fun `INV-AC-27139 unavailable at correction is Unavailable and no annotation is called`() {
        val f = Fixture(correction = down)
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Unavailable
        f.calls shouldBe listOf("holds(t1,u1,ENT-1)", "status(case-1)", "correct")
    }

    @Test
    fun `INV-AC-27139 annotation failing twice then landing makes three calls with one key`() {
        val f = Fixture(annotationReplies = listOf(down, down, OwnerReply.Ok(Unit)))
        f.useCase.handle(base) shouldBe corrected
        f.calls.count { it == "annotate" } shouldBe 3
        f.annotationKeys.toSet().size shouldBe 1
        f.calls.count { it == "correct" } shouldBe 1
    }

    @Test
    fun `INV-AC-27139 annotation failing three times is Unavailable with correction called once`() {
        val f = Fixture(annotationReplies = listOf(down))
        f.useCase.handle(base) shouldBe CorrectPersonalDataOutcome.Unavailable
        ANNOTATION_ATTEMPTS shouldBe 3
        f.calls.count { it == "annotate" } shouldBe 3
        f.calls.count { it == "correct" } shouldBe 1
        f.annotationKeys.toSet().size shouldBe 1
    }

    @Test
    fun `INV-AC-27139 keys are deterministic, v5, differ by owner and by each value`() {
        fun keys(c: CorrectPersonalDataCommand): Pair<String, String> {
            val f = Fixture()
            f.useCase.handle(c)
            return f.correctionKeys.single() to f.annotationKeys.single()
        }
        val (m, e) = keys(base)
        keys(base) shouldBe (m to e)
        (m != e) shouldBe true
        UUID.fromString(m).version() shouldBe 5
        UUID.fromString(e).version() shouldBe 5
        UUID.fromString(m).variant() shouldBe 2
        val variants =
            listOf(
                base.copy(caseId = "case-2"),
                base.copy(counterpartyRef = "CP-2"),
                base.copy(ackId = "ack-2"),
                base.copy(field = "postalCode"),
                base.copy(correctedValue = "Other"),
                base.copy(reason = "other reason"),
            )
        for (v in variants) {
            val (vm, ve) = keys(v)
            (vm != m) shouldBe true
            (ve != e) shouldBe true
        }
    }

    @Test
    fun `INV-AC-27139 annotation port never receives the corrected value`() {
        val f = Fixture()
        f.useCase.handle(base.copy(correctedValue = "SECRET-VALUE-9"))
        f.annotationArgs.flatten().none { it.contains("SECRET-VALUE-9") } shouldBe true
    }
}
