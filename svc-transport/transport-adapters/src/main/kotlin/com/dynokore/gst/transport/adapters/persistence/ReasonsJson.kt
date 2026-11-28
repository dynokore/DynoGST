package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.domain.FailureReason
import tools.jackson.databind.json.JsonMapper

/** The stored form of failure reasons: a JSON list of `{code, field, message}`, shared by both logs. */
internal object ReasonsJson {
    private val mapper = JsonMapper.builder().build()

    fun write(reasons: List<FailureReason>): String =
        mapper.writeValueAsString(
            reasons.map { mapOf("code" to it.code, "field" to it.field, "message" to it.message) },
        )

    fun read(json: String): List<FailureReason> =
        mapper.readTree(json).toList().map { node ->
            FailureReason(
                node.get("code").asString(),
                node.get("field")?.takeUnless { it.isNull }?.asString(),
                node.get("message").asString(),
            )
        }
}
