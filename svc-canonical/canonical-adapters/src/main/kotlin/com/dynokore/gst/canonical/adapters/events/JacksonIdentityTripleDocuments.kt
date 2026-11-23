package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.reprocessing.IdentityTripleDocuments
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * [IdentityTripleDocuments] over Jackson: reads and replaces the `value` of the `identityTriple` member of a canonical
 * document JSON (the shape [JacksonAggregateDocumentJson] writes: an identifying wrapper whose value holds
 * `senderId`, `supplierId` and `supplierGstn`). Nothing else of the JSON is touched.
 */
class JacksonIdentityTripleDocuments : IdentityTripleDocuments {
    private val mapper = JsonMapper.builder().build()

    override fun tripleOf(canonicalJson: String): SupplierIdentity? {
        val value = mapper.readTree(canonicalJson).path(MEMBER).path(VALUE)
        if (!value.isObject) return null
        val sender = text(value, "senderId")
        val supplier = text(value, "supplierId")
        val gstn = text(value, "supplierGstn")
        return if (sender == null || supplier == null ||
            gstn == null
        ) {
            null
        } else {
            SupplierIdentity(sender, supplier, gstn)
        }
    }

    override fun withTriple(
        canonicalJson: String,
        triple: SupplierIdentity,
    ): String {
        val root = mapper.readTree(canonicalJson) as? ObjectNode ?: error("the canonical JSON is not an object")
        val value = root.path(MEMBER).path(VALUE) as? ObjectNode ?: error("the canonical JSON has no identityTriple")
        value.put("senderId", triple.senderId)
        value.put("supplierId", triple.supplierId)
        value.put("supplierGstn", triple.supplierGstn)
        return mapper.writeValueAsString(root)
    }

    private fun text(
        node: JsonNode,
        member: String,
    ): String? = node.path(member).takeIf { it.isString }?.asString()

    private companion object {
        const val MEMBER = "identityTriple"
        const val VALUE = "value"
    }
}
