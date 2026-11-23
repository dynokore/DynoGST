package com.dynokore.gst.notification.domain.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DefaultChannelsTest {
    @Test
    fun `INV-AC-27663 deadline default channels are exactly EMAIL`() {
        DEADLINE_DEFAULT_CHANNELS shouldBe listOf("EMAIL")
    }

    @Test
    fun `INV-AC-27663 deadline default channels are allowed by the disable rule`() {
        DisableRule.decide(AlertClass.DEADLINE, DEADLINE_DEFAULT_CHANNELS) shouldBe DisableDecision.Allowed
    }
}
