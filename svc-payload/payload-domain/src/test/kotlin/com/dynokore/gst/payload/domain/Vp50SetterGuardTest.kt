package com.dynokore.gst.payload.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class Vp50SetterGuardTest {
    private val rely = WrongfulChargeCheck.RELY_ON_AP_CHECK
    private val system = WrongfulChargeCheck.SYSTEM_EVERY_PATH
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val good = ApCheckRecord("c-1", "u-1", at)

    private fun refused(reason: Vp50SetRefusal) = Vp50SetOutcome.Refused(reason)

    @Test
    fun `INV-AC-26640 G1 no record is refused NO_AP_CHECK_RECORD`() {
        Vp50SetterGuard.decide(rely, "c-1", null, true) shouldBe refused(Vp50SetRefusal.NO_AP_CHECK_RECORD)
    }

    @Test
    fun `INV-AC-26640 G2 record of another connection is refused RECORD_CONNECTION_MISMATCH`() {
        Vp50SetterGuard.decide(rely, "c-1", ApCheckRecord("c-2", "u-1", at), true) shouldBe
            refused(Vp50SetRefusal.RECORD_CONNECTION_MISMATCH)
    }

    @Test
    fun `INV-AC-26640 G3 blank actor is refused RECORD_WITHOUT_ACTOR`() {
        for (actor in listOf("", "   ")) {
            Vp50SetterGuard.decide(rely, "c-1", ApCheckRecord("c-1", actor, at), true) shouldBe
                refused(Vp50SetRefusal.RECORD_WITHOUT_ACTOR)
        }
    }

    @Test
    fun `INV-AC-26640 G4 good record without acknowledgement is refused`() {
        Vp50SetterGuard.decide(rely, "c-1", good, false) shouldBe refused(Vp50SetRefusal.ACKNOWLEDGEMENT_NOT_IN_FORCE)
    }

    @Test
    fun `INV-AC-26640 G5 good record and acknowledgement is accepted`() {
        Vp50SetterGuard.decide(rely, "c-1", good, true) shouldBe Vp50SetOutcome.Accepted
    }

    @Test
    fun `INV-AC-26640 G6 SYSTEM_EVERY_PATH is always accepted`() {
        Vp50SetterGuard.decide(system, "c-1", null, false) shouldBe Vp50SetOutcome.Accepted
        Vp50SetterGuard.decide(system, "c-1", good, true) shouldBe Vp50SetOutcome.Accepted
    }

    @Test
    fun `INV-AC-26640 G7 the first refusal in the stated order wins`() {
        Vp50SetterGuard.decide(rely, "c-1", null, false) shouldBe refused(Vp50SetRefusal.NO_AP_CHECK_RECORD)
    }
}
