package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.SchematronFile
import com.dynokore.gst.payload.domain.StagedPackSource
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/**
 * Reads the staged Schematron pack files of a version from the replica contents text (INV-REQ-776 behavior 3,
 * INV-REQ-1210). [read] returns the contents JSON of `schematron-pack-contents.schema.json` by version, or null.
 * Fail closed: a bad text, a hash mismatch, a duplicate name or an empty set is an [IllegalStateException], never a
 * partial set. No external resource is resolved.
 */
class StagedSchematronSource(
    private val read: (String) -> String?,
) : StagedPackSource {
    override fun fetch(packVersion: String): List<SchematronFile>? = read(packVersion)?.let { parse(it) }

    companion object {
        private val mapper = JsonMapper()

        /** Parses [contentsJson] into its files, each verified against its listed SHA-256. */
        fun parse(contentsJson: String): List<SchematronFile> {
            val root =
                try {
                    mapper.readTree(contentsJson)
                } catch (e: JacksonException) {
                    throw IllegalStateException("the staged Schematron contents are not JSON", e)
                }
            val files = root?.path("files")
            check(files != null && files.isArray && files.size() > 0) { "the staged Schematron contents hold no files" }
            val seen = HashSet<String>()
            val result = ArrayList<SchematronFile>()
            for (node in files) {
                val name = text(node, "name")
                check(NAME.matches(name)) { "a staged Schematron file name is not allowed" }
                check(seen.add(name)) { "duplicate staged Schematron file name $name" }
                val sha256 = text(node, "sha256")
                val bytes =
                    try {
                        Base64.getDecoder().decode(text(node, "content"))
                    } catch (e: IllegalArgumentException) {
                        throw IllegalStateException("the content of $name is not base64", e)
                    }
                val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                check(actual == sha256) { "the SHA-256 of $name differs from the listed one" }
                result += SchematronFile(name, bytes)
            }
            return result
        }

        private val NAME = Regex("^[A-Za-z0-9._-]+$")

        private fun text(
            node: JsonNode,
            field: String,
        ): String {
            val v = node.path(field)
            check(v.isString) { "a staged Schematron file lacks $field" }
            return v.asString()
        }
    }
}
