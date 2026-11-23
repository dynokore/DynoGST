package com.dynokore.gst.payload.domain

/**
 * The VP-26 pre-issuance check selection (INV-REQ-1287).
 * The default ADVISORY is applied by the caller that reads the setting.
 */
enum class PreIssuanceMode { ADVISORY, BLOCKING_IN_ERP, OFF }

/**
 * Maps a decided pre-issuance outcome by the VP-26 mode, pure.
 * A refused outcome is returned unchanged for every mode.
 */
object PreIssuanceModeMapping {
    fun apply(
        mode: PreIssuanceMode,
        outcome: PreIssuanceOutcome,
    ): PreIssuanceOutcome =
        when (outcome) {
            is PreIssuanceOutcome.Refused -> {
                outcome
            }

            is PreIssuanceOutcome.Decided -> {
                when (mode) {
                    PreIssuanceMode.BLOCKING_IN_ERP -> {
                        outcome
                    }

                    PreIssuanceMode.ADVISORY -> {
                        if (outcome.verdict == PreIssuanceVerdict.BLOCK) {
                            outcome.copy(verdict = PreIssuanceVerdict.WARN)
                        } else {
                            outcome
                        }
                    }

                    PreIssuanceMode.OFF -> {
                        PreIssuanceOutcome.Decided(PreIssuanceVerdict.PASS, emptyList(), emptySet())
                    }
                }
            }
        }
}
