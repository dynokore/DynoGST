package com.dynokore.gst.consoleBff.application.override

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreviewOverrideTest {
    private val tenant = "t1"
    private val allowed = "u-allowed"
    private val refused = "u-refused"
    private val refusal = OwnerRefusal(404, "DOC-001", "Not found", "BLOCK", "TERMINAL", "OWNER", "detail")

    /** Stands for a code whose disposition is TERMINAL: the catalogue port has no texts for it. */
    private val terminal = "PAY-017"

    private fun row(
        id: String,
        code: String,
        state: String = "OPEN",
        doc: String = "d-$id",
        period: String = "",
    ) = OverridableRow(id, "ENT-1", doc, period, code, state)

    private val rows =
        listOf(
            row("pay", "PAY-006"),
            row("per", "PER-001", doc = "", period = "2026-Q3"),
            // A docUuid on a period code, so only the route refuses it.
            row("perDoc", "PER-001", period = "2026-Q3"),
            row("mdm", "MDM-003"),
            row("terminal", terminal),
            // A PAYLOAD route without catalogue texts, so only the texts refuse it.
            row("pay011", "PAY-011"),
            row("resolved", "PAY-006", state = "RESOLVED"),
            row("overridden", "PAY-006", state = "OVERRIDDEN"),
            row("lowerOpen", "PAY-006", state = "open"),
            row("noState", "PAY-006", state = ""),
            row("noDoc", "PAY-006", doc = ""),
            row("longer", "PAY-0060"),
            row("lower", "pay-006"),
        ).associateBy { it.exceptionId }

    private val catalogue =
        listOf("PAY-006", "PER-001", "MDM-003").associateWith {
            ConsequenceText("transmits $it", "risk of $it")
        }

    private class Fixture(
        rows: Map<String, OverridableRow>,
        texts: Map<String, ConsequenceText>,
        allowed: String,
        read: OwnerReply<Long>,
    ) {
        val lookups = mutableListOf<String>()
        val ownerCalls = mutableListOf<String>()
        private val payload =
            object : PayloadOverrides {
                override fun latestVersion(docUuid: String): OwnerReply<Long> {
                    ownerCalls += "payload.latestVersion($docUuid)"
                    return read
                }

                override fun override(
                    docUuid: String,
                    version: Long,
                    code: String,
                    reason: String,
                ): OwnerReply<Release> {
                    ownerCalls += "payload.override($docUuid)"
                    throw AssertionError("the preview must not call override")
                }
            }

        /** The use case takes no submission port; this fake fails any wiring that would give it one and call it. */
        @Suppress("unused")
        val submission =
            object : SubmissionOverrides {
                override fun latestCheck(
                    entityRef: String,
                    periodRef: String,
                ): OwnerReply<String> {
                    ownerCalls += "submission.latestCheck($entityRef,$periodRef)"
                    throw AssertionError("the preview must not call submission")
                }

                override fun override(
                    entityRef: String,
                    periodRef: String,
                    checkId: String,
                    reason: String,
                ): OwnerReply<Release> {
                    ownerCalls += "submission.override($entityRef,$periodRef)"
                    throw AssertionError("the preview must not call submission")
                }
            }

        val useCase =
            PreviewOverride(
                { id ->
                    lookups += "find($id)"
                    rows[id]
                },
                { t, u, e ->
                    lookups += "holds($t,$u,$e)"
                    u == allowed
                },
                { code ->
                    lookups += "texts($code)"
                    texts[code]
                },
                payload,
            )
    }

    private fun fixture(
        read: OwnerReply<Long> = OwnerReply.Ok(7L),
        texts: Map<String, ConsequenceText> = catalogue,
    ) = Fixture(rows, texts, allowed, read)

    private fun preview(
        f: Fixture,
        id: String,
        user: String = allowed,
    ) = f.useCase.preview(tenant, user, id)

    private fun assertRefusedWithoutOwnerCall(
        id: String,
        expected: PreviewOutcome,
        f: Fixture = fixture(),
    ) {
        preview(f, id) shouldBe expected
        f.ownerCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26585 PAY-006 allowed is Previewed from the row, one version read, the texts and the authority`() {
        val f = fixture()
        preview(f, "pay") shouldBe
            PreviewOutcome.Previewed(
                exceptionId = "pay",
                docUuid = "d-pay",
                docVersion = 7L,
                code = "PAY-006",
                permissionKey = "validation.override",
                decision = PreviewDecision.ALLOW,
                approvalKind = PreviewApprovalKind.ONE_APPROVER,
                willTransmit = "transmits PAY-006",
                residualRisk = "risk of PAY-006",
            )
        f.ownerCalls shouldBe listOf("payload.latestVersion(d-pay)")
        f.lookups shouldBe listOf("find(pay)", "holds(t1,u-allowed,ENT-1)", "texts(PAY-006)")
    }

    @Test
    fun `INV-AC-26585 docVersion is the value of the read, including the lowest valid version 1`() {
        for (version in listOf(1L, 42L, Long.MAX_VALUE)) {
            val outcome = preview(fixture(read = OwnerReply.Ok(version)), "pay")
            (outcome as PreviewOutcome.Previewed).docVersion shouldBe version
        }
    }

    @Test
    fun `INV-AC-26585 PER-001 is NotPreviewable with no owner call`() {
        assertRefusedWithoutOwnerCall("per", PreviewOutcome.NotPreviewable)
        assertRefusedWithoutOwnerCall("perDoc", PreviewOutcome.NotPreviewable)
    }

    @Test
    fun `INV-AC-26585 MDM-003 without a route is NotPreviewable with no owner call`() {
        assertRefusedWithoutOwnerCall("mdm", PreviewOutcome.NotPreviewable)
    }

    @Test
    fun `INV-AC-26585 a TERMINAL code is NotPreviewable with no owner call`() {
        assertRefusedWithoutOwnerCall("terminal", PreviewOutcome.NotPreviewable)
    }

    @Test
    fun `INV-AC-26585 a routed code without catalogue texts is NotPreviewable with no owner call`() {
        assertRefusedWithoutOwnerCall("pay011", PreviewOutcome.NotPreviewable)
    }

    @Test
    fun `INV-AC-26585 look-alike codes are not routed even when texts exist for them`() {
        val texts = listOf("PAY-0060", "pay-006").associateWith { ConsequenceText("w", "r") }
        for (id in listOf("longer", "lower")) {
            assertRefusedWithoutOwnerCall(id, PreviewOutcome.NotPreviewable, fixture(texts = texts))
        }
    }

    @Test
    fun `INV-AC-26585 a refused caller is Forbidden before any other lookup and with no owner call`() {
        for (id in listOf("pay", "per", "mdm", "terminal", "noDoc")) {
            val f = fixture()
            preview(f, id, refused) shouldBe PreviewOutcome.Forbidden
            f.ownerCalls shouldBe emptyList()
            f.lookups shouldBe listOf("find($id)", "holds(t1,u-refused,ENT-1)")
        }
    }

    @Test
    fun `INV-AC-26585 an unknown exception id is NotFound`() {
        val f = fixture()
        preview(f, "nope") shouldBe PreviewOutcome.NotFound
        f.ownerCalls shouldBe emptyList()
        f.lookups shouldBe listOf("find(nope)")
    }

    @Test
    fun `INV-AC-26585 a state other than OPEN is NotFound`() {
        for (id in listOf("resolved", "overridden", "lowerOpen", "noState")) {
            val f = fixture()
            preview(f, id) shouldBe PreviewOutcome.NotFound
            f.ownerCalls shouldBe emptyList()
            f.lookups shouldBe listOf("find($id)")
        }
    }

    @Test
    fun `INV-AC-26585 an empty docUuid is NotFound with no owner call`() {
        assertRefusedWithoutOwnerCall("noDoc", PreviewOutcome.NotFound)
    }

    @Test
    fun `INV-AC-26585 a Refused read is Relayed with the same refusal`() {
        val f = fixture(read = OwnerReply.Refused(refusal))
        preview(f, "pay") shouldBe PreviewOutcome.Relayed(refusal)
        f.ownerCalls shouldBe listOf("payload.latestVersion(d-pay)")
    }

    @Test
    fun `INV-AC-26585 an Unavailable read is Unavailable`() {
        val f = fixture(read = OwnerReply.Unavailable("down"))
        preview(f, "pay") shouldBe PreviewOutcome.Unavailable("down")
        f.ownerCalls shouldBe listOf("payload.latestVersion(d-pay)")
    }

    @Test
    fun `INV-AC-26585 version 0 and version -1 are Unavailable, never Previewed`() {
        for (version in listOf(0L, -1L)) {
            val f = fixture(read = OwnerReply.Ok(version))
            preview(f, "pay") shouldBe PreviewOutcome.Unavailable("document version below 1")
            f.ownerCalls shouldBe listOf("payload.latestVersion(d-pay)")
        }
    }
}
