package com.dynokore.gst.notification.domain.webhook

import com.dynokore.gst.notification.domain.webhook.SubscribeDecision.Accepted
import com.dynokore.gst.notification.domain.webhook.SubscribeDecision.Refused
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebhookSubscribeRuleTest {
    private companion object {
        const val EVENT = "AcknowledgementReceived"
    }

    @Test
    fun `INV-AC-2295 a new tenant has no subscription option and subscribing is refused while the point is planned`() {
        WebhookSubscribeRule.DEFAULT shouldBe SubscribeOption.NONE
        WebhookSubscribeRule.decide(SubscribeOption.BY_EVENT_TYPE, EVENT, pointAvailable = false) shouldBe
            Refused(SubscribeRefusal.POINT_PLANNED)
        WebhookSubscribeRule.decide(SubscribeOption.NONE, null, pointAvailable = false) shouldBe
            Refused(SubscribeRefusal.NO_EVENT_TYPE)
    }

    @Test
    fun `by event type with the point available and a type name is accepted with that type`() {
        WebhookSubscribeRule.decide(SubscribeOption.BY_EVENT_TYPE, EVENT, pointAvailable = true) shouldBe
            Accepted(EVENT)
    }

    @Test
    fun `a null empty or blank type with the point available is refused`() {
        listOf(null, "", "   ").forEach {
            WebhookSubscribeRule.decide(SubscribeOption.BY_EVENT_TYPE, it, pointAvailable = true) shouldBe
                Refused(SubscribeRefusal.NO_EVENT_TYPE)
        }
    }

    @Test
    fun `the planned refusal wins over a missing type`() {
        WebhookSubscribeRule.decide(SubscribeOption.BY_EVENT_TYPE, null, pointAvailable = false) shouldBe
            Refused(SubscribeRefusal.POINT_PLANNED)
    }

    @Test
    fun `option none refuses whatever the type and availability`() {
        listOf(true, false).forEach {
            WebhookSubscribeRule.decide(SubscribeOption.NONE, EVENT, it) shouldBe
                Refused(SubscribeRefusal.NO_EVENT_TYPE)
        }
    }
}
