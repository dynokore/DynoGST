package com.dynokore.gst.notification.domain.alerting

const val DEADLINE_ALERT_TEMPLATE = "DEADLINE_ALERT"

data class MessageTemplate(
    val id: String,
    val version: Int,
    val parameters: Set<String>,
)

enum class RenderRefusal { UNKNOWN_TEMPLATE, UNKNOWN_PARAMETER }

sealed interface RenderResult {
    data class Rendered(
        val text: String,
    ) : RenderResult

    data class Refused(
        val reason: RenderRefusal,
    ) : RenderResult
}

/** Closed, versioned registry of the message templates owned by notification-svc (INV-REQ-818). */
object MessageTemplates {
    private const val FIRST_VERSION = 1

    // setOf keeps insertion order, which is the render order.
    val registry: List<MessageTemplate> =
        listOf(
            MessageTemplate(
                ACCREDITATION_ALERT_TEMPLATE,
                FIRST_VERSION,
                setOf("reason", "taskRef", "releaseRef", "currentUntil"),
            ),
            MessageTemplate(BREAK_GLASS_TEMPLATE, FIRST_VERSION, setOf("scope", "action", "unlockRef")),
            MessageTemplate(
                PRIVACY_NOTICE_TEMPLATE,
                FIRST_VERSION,
                setOf("caseId", "requestKind", "responseDeadline"),
            ),
            MessageTemplate(
                DEADLINE_ALERT_TEMPLATE,
                FIRST_VERSION,
                setOf("trigger", "tier", "entityRef", "periodRef"),
            ),
        )

    private val byId: Map<String, MessageTemplate> = registry.associateBy { it.id }

    fun render(
        templateId: String,
        parameters: Map<String, String>,
    ): RenderResult {
        val template = byId[templateId]
        return when {
            template == null -> RenderResult.Refused(RenderRefusal.UNKNOWN_TEMPLATE)
            !template.parameters.containsAll(parameters.keys) -> RenderResult.Refused(RenderRefusal.UNKNOWN_PARAMETER)
            else -> RenderResult.Rendered(text(template, parameters))
        }
    }

    private fun text(
        template: MessageTemplate,
        parameters: Map<String, String>,
    ): String {
        val pairs = template.parameters.filter { it in parameters }.map { "$it=${parameters.getValue(it)}" }
        return (listOf(template.id, template.version.toString()) + pairs).joinToString("\n")
    }
}
