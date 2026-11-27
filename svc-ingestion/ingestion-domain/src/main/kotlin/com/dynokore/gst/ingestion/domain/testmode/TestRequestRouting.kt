package com.dynokore.gst.ingestion.domain.testmode

enum class WorkspaceKind { PRODUCTION, NON_PRODUCTION }

sealed interface TestRouting {
    data object Live : TestRouting

    data object InRequestWorkspace : TestRouting

    data class Refused(
        val code: String,
        val httpStatus: Int,
    ) : TestRouting
}

object TestRequestRouting {
    const val ING_021 = "ING-021"
    const val UNPROCESSABLE = 422

    fun route(
        headerMarked: Boolean,
        requestWorkspace: WorkspaceKind,
    ): TestRouting =
        when (requestWorkspace) {
            WorkspaceKind.NON_PRODUCTION -> {
                TestRouting.InRequestWorkspace
            }

            WorkspaceKind.PRODUCTION -> {
                when (headerMarked) {
                    false -> TestRouting.Live
                    true -> TestRouting.Refused(ING_021, UNPROCESSABLE)
                }
            }
        }
}
