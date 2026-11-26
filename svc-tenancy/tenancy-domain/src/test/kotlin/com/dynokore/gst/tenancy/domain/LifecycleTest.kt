package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure rules of the lifecycle changes. */
class LifecycleTest {
    private val met =
        GoLiveFacts(
            emptyList(),
            CertificationOutcome.PASSED,
            emptyList(),
            emptyList(),
            missingRenditionClasses = emptyList(),
            renditionUnknown = false,
            tenantOpen = true,
        )

    private fun renditionItem(documentClass: String) = "RENDITION_RESPONSIBILITY_INCOMPLETE: $documentClass"

    @Test
    fun `INV-AC-27909 no record gives one item per document class in the order of the list`() {
        val unmet = GoLiveGate.unmet(met.copy(missingRenditionClasses = DocumentClasses.ALL.reversed()))
        assertEquals(listOf("TYPE_1A", "TYPE_1B", "TYPE_2", "TYPE_3").map(::renditionItem), unmet)
    }

    @Test
    fun `INV-AC-27909 only the missing classes are named and none when all four are current`() {
        assertEquals(
            listOf(renditionItem("TYPE_1B"), renditionItem("TYPE_3")),
            GoLiveGate.unmet(met.copy(missingRenditionClasses = listOf("TYPE_1B", "TYPE_3"))),
        )
        assertEquals(emptyList<String>(), GoLiveGate.unmet(met.copy(missingRenditionClasses = emptyList())))
    }

    @Test
    fun `INV-AC-27909 unreadable records give the unknown item and no per-class item`() {
        val facts = met.copy(missingRenditionClasses = DocumentClasses.ALL, renditionUnknown = true)
        assertEquals(listOf("RENDITION_RESPONSIBILITY_UNKNOWN"), GoLiveGate.unmet(facts))
    }

    @Test
    fun `INV-AC-27909 the rendition items follow the existing items`() {
        val facts =
            met.copy(
                tenantOpen = false,
                outstandingElections = listOf("ISSUANCE_MODE"),
                missingRenditionClasses = listOf("TYPE_2"),
            )
        assertEquals(
            listOf("tenant not open", "required elections outstanding: ISSUANCE_MODE", renditionItem("TYPE_2")),
            GoLiveGate.unmet(facts),
        )
    }

    @Test
    fun `go-live gate with every item met names nothing`() {
        assertEquals(emptyList<String>(), GoLiveGate.unmet(met))
    }

    @Test
    fun `INV-AC-19391 go-live gate names tenant not open first and beside the other items`() {
        assertEquals(listOf(GoLiveGate.TENANT_NOT_OPEN), GoLiveGate.unmet(met.copy(tenantOpen = false)))
        val both = GoLiveGate.unmet(met.copy(tenantOpen = false, uncoveredTriggers = listOf("T1")))
        assertEquals(2, both.size)
        assertEquals("tenant not open", both[0])
    }

    @Test
    fun `go-live gate names each unmet item`() {
        val unmet =
            GoLiveGate.unmet(
                GoLiveFacts(
                    listOf("T1"),
                    CertificationOutcome.FAILED,
                    listOf("s1", "s2"),
                    listOf("ISSUANCE_MODE"),
                    missingRenditionClasses = emptyList(),
                    renditionUnknown = false,
                    tenantOpen = true,
                ),
            )
        assertEquals(3, unmet.size)
        assertTrue(unmet[0].contains("T1"))
        assertTrue(unmet[1].contains("s1") && unmet[1].contains("s2"))
        assertTrue(unmet[2].contains("ISSUANCE_MODE"))
    }

    @Test
    fun `go-live gate without a certification record names the sandbox certification`() {
        assertTrue(GoLiveGate.unmet(met.copy(certification = null)).single().contains("sandbox certification"))
    }

    @Test
    fun `go-live is a repeat only for the exact state LIVE`() {
        assertTrue(LifecycleRules.goLiveIsRepeat("LIVE"))
        assertFalse(LifecycleRules.goLiveIsRepeat("live"))
        assertFalse(LifecycleRules.goLiveIsRepeat("SUSPENDED"))
    }

    @Test
    fun `deregistration is blocked by an unsubmitted document unless an acknowledgement is recorded`() {
        val ack = UnsubmittedOverride("identity:USR-004", "accepted")
        assertTrue(LifecycleRules.deregistrationBlocked(1, null))
        assertFalse(LifecycleRules.deregistrationBlocked(1, ack))
        assertFalse(LifecycleRules.deregistrationBlocked(0, null))
    }
}
