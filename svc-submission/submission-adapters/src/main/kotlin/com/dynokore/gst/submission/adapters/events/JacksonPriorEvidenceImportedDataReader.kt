package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.migration.PriorEvidenceImportedData
import com.dynokore.gst.submission.application.migration.PriorEvidenceImportedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [PriorEvidenceImportedDataReader] over Jackson, for prior-submission-evidence-imported-payload.schema.json
 * (platform-contracts bundle 6b14a56e). An extra property is tolerated; a missing or malformed member is a
 * [ContractViolation] whose message names the member, never its value.
 */
class JacksonPriorEvidenceImportedDataReader : PriorEvidenceImportedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): PriorEvidenceImportedData {
        val data = parse(dataJson)
        return PriorEvidenceImportedData(
            entityRef = data.text("entityRef"),
            documentNumber = data.text("documentNumber"),
            transactionDate = dateOf(data.text("transactionDate")),
            acknowledgementRef = data.text("acknowledgementRef"),
            priorSolution = data.text("priorSolution"),
        )
    }

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("PriorSubmissionEvidenceImported data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("PriorSubmissionEvidenceImported data is not a JSON object")
        }
        return data
    }

    private fun dateOf(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("PriorSubmissionEvidenceImported transactionDate is not a date", e)
        }

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isBlank()) {
            throw ContractViolation(
                "PriorSubmissionEvidenceImported data member $name is missing or not a non-blank string",
            )
        }
        return node.asString()
    }
}
