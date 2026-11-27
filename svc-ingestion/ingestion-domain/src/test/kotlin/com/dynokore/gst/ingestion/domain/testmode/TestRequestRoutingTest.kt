package com.dynokore.gst.ingestion.domain.testmode

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TestRequestRoutingTest {
    private val refused = TestRouting.Refused("ING-021", 422)

    private fun route(
        marked: Boolean,
        workspace: WorkspaceKind,
    ) = TestRequestRouting.route(marked, workspace)

    @Test
    fun `INV-AC-14108 marked non-production request gives InRequestWorkspace`() {
        route(true, WorkspaceKind.NON_PRODUCTION) shouldBe TestRouting.InRequestWorkspace
    }

    @Test
    fun `INV-AC-14108 unmarked non-production request gives InRequestWorkspace`() {
        route(false, WorkspaceKind.NON_PRODUCTION) shouldBe TestRouting.InRequestWorkspace
    }

    @Test
    fun `INV-AC-14108 unmarked production request gives Live`() {
        route(false, WorkspaceKind.PRODUCTION) shouldBe TestRouting.Live
    }

    @Test
    fun `INV-AC-14108 marked production request is refused with ING-021 and 422`() {
        route(true, WorkspaceKind.PRODUCTION) shouldBe refused
    }

    @Test
    fun `INV-AC-14108 a marked production request never gives Live`() {
        route(true, WorkspaceKind.PRODUCTION) shouldNotBe TestRouting.Live
    }

    @Test
    fun `INV-AC-14108 the constants are ING-021 and 422`() {
        TestRequestRouting.ING_021 shouldBe "ING-021"
        TestRequestRouting.UNPROCESSABLE shouldBe 422
    }
}
