package com.dynokore.gst.consoleBff.adapters.index

import com.dynokore.gst.consoleBff.application.index.CanonicalVersionCreatedReader
import com.dynokore.gst.consoleBff.application.index.CanonicalVersionFacts
import com.dynokore.gst.consoleBff.application.index.MasterDataResolvedUenReader
import com.dynokore.gst.consoleBff.application.index.ResolvedUen
import com.dynokore.gst.consoleBff.application.index.SubmissionAcknowledgedReader
import com.dynokore.gst.consoleBff.application.index.SubmissionDespatchedReader
import com.dynokore.gst.consoleBff.application.index.SubmissionFailedIndexFacts
import com.dynokore.gst.consoleBff.application.index.SubmissionFailedIndexReader
import com.dynokore.gst.consoleBff.application.index.SubmissionVersionFacts
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Shared parsing helpers of the document index payload readers. */
internal object IndexPayload {
    private val mapper = ObjectMapper()

    fun parse(
        dataJson: String,
        type: String,
    ): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("$type data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("$type data is not a JSON object")
        return data
    }

    fun string(
        node: JsonNode?,
        path: String,
        type: String,
    ): String? {
        if (node == null || node.isNull || node.isMissingNode) return null
        if (!node.isString) throw ContractViolation("$type member $path is not a string")
        return node.asString()
    }

    fun requiredString(
        data: JsonNode,
        name: String,
        type: String,
    ): String =
        string(data.get(name), name, type)?.takeIf { it.isNotEmpty() }
            ?: throw ContractViolation("$type data member $name is missing or not a non-empty string")

    fun docUuid(
        data: JsonNode,
        type: String,
    ): String {
        val text = requiredString(data, "docUuid", type)
        try {
            java.util.UUID.fromString(text)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("$type data member docUuid is not a UUID", e)
        }
        return text
    }

    fun version(
        data: JsonNode,
        type: String,
    ): Long {
        val node = data.get("version")
        val valid = node != null && node.isIntegralNumber && node.canConvertToLong() && node.asLong() >= 1
        if (!valid) throw ContractViolation("$type data member version is missing or not an integer of at least 1")
        return node.asLong()
    }

    fun date(
        node: JsonNode?,
        path: String,
        type: String,
    ): LocalDate? {
        val text = string(node, path, type) ?: return null
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$type member $path is not a date", e)
        }
    }

    /** The member `name` of an object node, null when the node is absent, null or not an object. */
    fun child(
        node: JsonNode?,
        name: String,
    ): JsonNode? = node?.takeIf { it.isObject }?.get(name)?.takeIf { !it.isNull }
}

/** [CanonicalVersionCreatedReader] over Jackson (canonical-version-created-payload.schema.json). */
class JacksonCanonicalVersionCreatedReader : CanonicalVersionCreatedReader {
    override fun read(dataJson: String): CanonicalVersionFacts {
        val data = IndexPayload.parse(dataJson, TYPE)
        val doc = IndexPayload.child(data, "canonicalDocument")
        val direction =
            IndexPayload.string(
                IndexPayload.child(data.get("routingFacts"), "direction"),
                "direction",
                TYPE,
            )
        val parties = value(doc, "parties")
        val party =
            when (direction) {
                "SALE" -> IndexPayload.child(parties, "buyer")
                "PURCHASE" -> IndexPayload.child(parties, "supplier")
                else -> null
            }
        val scheme = IndexPayload.string(IndexPayload.child(party, "identifierScheme"), "identifierScheme", TYPE)
        val uen =
            if (scheme == "SG_UEN") {
                IndexPayload.string(IndexPayload.child(party, "identifierValue"), "identifierValue", TYPE)
            } else {
                null
            }
        val transactionDate =
            IndexPayload.date(data.get("transactionDate"), "transactionDate", TYPE)
                ?: throw ContractViolation("$TYPE data member transactionDate is missing")
        return CanonicalVersionFacts(
            docUuid = IndexPayload.docUuid(data, TYPE),
            version = IndexPayload.version(data, TYPE),
            entityRef = IndexPayload.string(data.get("entityRef"), "entityRef", TYPE),
            documentNumber = IndexPayload.string(value(doc, "documentNumber"), "documentNumber.value", TYPE),
            counterpartyName = IndexPayload.string(IndexPayload.child(party, "name"), "name", TYPE),
            counterpartyUen = uen,
            amount = amount(value(doc, "totals")),
            documentDate = IndexPayload.date(value(doc, "documentDate"), "documentDate.value", TYPE),
            categoryCodes = categoryCodes(value(doc, "taxSubtotals")),
            scopeVerdict = IndexPayload.string(data.get("scopeVerdict"), "scopeVerdict", TYPE),
            transactionDate = transactionDate,
        )
    }

    /** The `value` member of the canonical document member `name`. */
    private fun value(
        doc: JsonNode?,
        name: String,
    ): JsonNode? = IndexPayload.child(IndexPayload.child(doc, name), "value")

    private fun amount(totals: JsonNode?): BigDecimal? {
        val text =
            IndexPayload.string(IndexPayload.child(totals, "totalIncludingTax"), "totalIncludingTax", TYPE)
                ?: return null
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw ContractViolation("$TYPE member totalIncludingTax is not a decimal", e)
        }
    }

    private fun categoryCodes(subtotals: JsonNode?): Set<String> {
        if (subtotals == null) return emptySet()
        if (!subtotals.isArray) throw ContractViolation("$TYPE member taxSubtotals.value is not an array")
        return subtotals
            .mapNotNull { IndexPayload.string(IndexPayload.child(it, "taxCode"), "taxCode", TYPE) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private companion object {
        const val TYPE = "CanonicalVersionCreated"
    }
}

/** [SubmissionDespatchedReader] over Jackson (submission-despatched-payload.schema.json). */
class JacksonSubmissionDespatchedReader : SubmissionDespatchedReader {
    override fun read(dataJson: String): SubmissionVersionFacts {
        val data = IndexPayload.parse(dataJson, TYPE)
        return SubmissionVersionFacts(IndexPayload.docUuid(data, TYPE), IndexPayload.version(data, TYPE))
    }

    private companion object {
        const val TYPE = "SubmissionDespatched"
    }
}

/** [SubmissionAcknowledgedReader] over Jackson (submission-acknowledged-payload.schema.json). */
class JacksonSubmissionAcknowledgedReader : SubmissionAcknowledgedReader {
    override fun read(dataJson: String): SubmissionVersionFacts {
        val data = IndexPayload.parse(dataJson, TYPE)
        return SubmissionVersionFacts(
            IndexPayload.docUuid(data, TYPE),
            IndexPayload.version(data, TYPE),
            IndexPayload.requiredString(data, "acknowledgementId", TYPE),
        )
    }

    private companion object {
        const val TYPE = "SubmissionAcknowledged"
    }
}

/** [SubmissionFailedIndexReader] over Jackson (submission-failed-payload.schema.json): `code` null when absent. */
class JacksonSubmissionFailedIndexReader : SubmissionFailedIndexReader {
    override fun read(dataJson: String): SubmissionFailedIndexFacts {
        val data = IndexPayload.parse(dataJson, TYPE)
        return SubmissionFailedIndexFacts(
            IndexPayload.docUuid(data, TYPE),
            IndexPayload.version(data, TYPE),
            IndexPayload.string(data.get("code"), "code", TYPE)?.takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val TYPE = "SubmissionFailed"
    }
}

/** [MasterDataResolvedUenReader] over Jackson (master-data-resolved-payload.schema.json). */
class JacksonMasterDataResolvedUenReader : MasterDataResolvedUenReader {
    override fun read(dataJson: String): ResolvedUen {
        val data = IndexPayload.parse(dataJson, TYPE)
        val docs = data.get("docUuids")
        val docUuids =
            if (docs == null || docs.isNull) {
                emptyList()
            } else {
                if (!docs.isArray || docs.any { !it.isString || it.asString().isBlank() }) {
                    throw ContractViolation("$TYPE data member docUuids is not an array of non-blank strings")
                }
                docs.asIterable().map { it.asString() }.distinct()
            }
        val identifiers = data.get("resolvedIdentifiers")
        if (identifiers != null && !identifiers.isNull && !identifiers.isArray) {
            throw ContractViolation("$TYPE data member resolvedIdentifiers is not an array")
        }
        val uen =
            identifiers
                ?.firstOrNull { IndexPayload.string(IndexPayload.child(it, "scheme"), "scheme", TYPE) == "UEN" }
                ?.let { IndexPayload.string(IndexPayload.child(it, "value"), "value", TYPE) }
        return ResolvedUen(docUuids, uen)
    }

    private companion object {
        const val TYPE = "MasterDataResolved"
    }
}
