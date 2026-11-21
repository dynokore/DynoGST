package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.RenderResult.Refused
import com.dynokore.gst.notification.domain.alerting.RenderResult.Rendered
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class MessageTemplatesTest {
    private fun params(template: String) =
        MessageTemplates.registry
            .first { it.id == template }
            .parameters

    @Test
    fun `INV-AC-1871 the registry lists exactly the four template ids, each with version 1`() {
        MessageTemplates.registry.map { it.id }.toSet() shouldBe
            setOf("ACCREDITATION_ALERT", "BREAK_GLASS_ALERT", "PRIVACY_NOTICE_DUE", "DEADLINE_ALERT")
        MessageTemplates.registry.size shouldBe 4
        MessageTemplates.registry.map { it.version } shouldBe listOf(1, 1, 1, 1)
    }

    @Test
    fun `INV-AC-1871 each parameter set contains identifiers, states, dates and link references only`() {
        MessageTemplates.registry.flatMap { it.parameters }.toSet() shouldBe
            setOf(
                "reason",
                "taskRef",
                "releaseRef",
                "currentUntil",
                "scope",
                "action",
                "unlockRef",
                "caseId",
                "requestKind",
                "responseDeadline",
                "trigger",
                "tier",
                "entityRef",
                "periodRef",
            )
    }

    @Test
    fun `INV-AC-1871 a rendered deadline alert holds only its four parameters`() {
        val result =
            MessageTemplates.render(
                DEADLINE_ALERT_TEMPLATE,
                mapOf("periodRef" to "2026-Q3", "tier" to "2", "trigger" to "PAST_DUE", "entityRef" to "e-1"),
            )
        (result as Rendered).text.lines() shouldBe
            listOf("DEADLINE_ALERT", "1", "trigger=PAST_DUE", "tier=2", "entityRef=e-1", "periodRef=2026-Q3")
    }

    @Test
    @DisplayName(
        "INV-AC-26512 the registered parameter sets of the three existing templates " +
            "equal the fields of their parameter holders",
    )
    fun `INV-AC-26512 registered sets equal the holder fields`() {
        fun fields(type: Class<*>) =
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet() - "template"
        fields(AccreditationMessageParameters::class.java) shouldBe params(ACCREDITATION_ALERT_TEMPLATE)
        fields(BreakGlassMessageParameters::class.java) shouldBe params(BREAK_GLASS_TEMPLATE)
        fields(PrivacyNoticeMessageParameters::class.java) shouldBe params(PRIVACY_NOTICE_TEMPLATE)
    }

    @Test
    fun `INV-AC-26512 an extra parameter named amount is refused`() {
        MessageTemplates.render(
            DEADLINE_ALERT_TEMPLATE,
            mapOf("trigger" to "PAST_DUE", "amount" to "10.00"),
        ) shouldBe Refused(RenderRefusal.UNKNOWN_PARAMETER)
    }

    @Test
    fun `INV-AC-26512 a template id that differs in case or by one character from a registered id is refused`() {
        listOf("deadline_alert", "DEADLINE_ALERTS", "DEADLINE_ALERT ", "").forEach {
            MessageTemplates.render(it, emptyMap()) shouldBe Refused(RenderRefusal.UNKNOWN_TEMPLATE)
        }
    }

    @Test
    fun `INV-AC-26512 the same parameter name in another template is refused`() {
        MessageTemplates.render(PRIVACY_NOTICE_TEMPLATE, mapOf("trigger" to "PAST_DUE")) shouldBe
            Refused(RenderRefusal.UNKNOWN_PARAMETER)
        MessageTemplates.render(DEADLINE_ALERT_TEMPLATE, mapOf("Trigger" to "x")) shouldBe
            Refused(RenderRefusal.UNKNOWN_PARAMETER)
    }

    @Test
    fun `INV-AC-26512 each registered template renders its own full parameter set`() {
        MessageTemplates.registry.forEach { t ->
            val text = (MessageTemplates.render(t.id, t.parameters.associateWith { "v" }) as Rendered).text
            text.lines() shouldBe listOf(t.id, "1") + t.parameters.map { "$it=v" }
        }
    }
}
