package com.dynokore.gst.payload.adapters.build

import com.dynokore.platform.integrationcanon.xml.HardenedXml
import net.sf.saxon.s9api.Processor
import org.xml.sax.InputSource
import javax.xml.transform.sax.SAXSource

/**
 * Reads the `location` of a failed assert of an SVRL report (INV-AC-27232): the local name of its last step and the
 * string value of the node it selects in the payload bytes. The payload is parsed with the hardened parser of
 * [HardenedXml], so no external entity, document type or external resource is ever read (OWASP XXE prevention).
 */
object SvrlLocation {
    private val processor = Processor(false)

    /**
     * The local name of the last step of [location]: the text after the last `/` that is outside every `[...]`
     * predicate, `{...}` URI literal and quoted string, with the predicates and the `Q{...}`, `prefix:` or `*:`
     * qualifier dropped (a `{...}` literal is dropped whole, so a `:` or `/` inside it never matters).
     */
    fun lastStepName(location: String): String {
        val step = location.substring(lastSlash(location) + 1)
        val kept = dropPredicates(step).let { if (step.startsWith("Q{")) it.drop(1) else it }
        return kept.substringAfterLast(':').removePrefix("@").trim()
    }

    /** The string value of the first node [location] selects in [document], or the empty string when none. */
    fun valueOf(
        document: ByteArray,
        location: String,
    ): String {
        val reader =
            HardenedXml
                .saxParserFactory()
                .also { it.isNamespaceAware = true }
                .newSAXParser()
                .xmlReader
        val source = SAXSource(reader, InputSource(document.inputStream()))
        val root = processor.newDocumentBuilder().build(source)
        val selector = processor.newXPathCompiler().compile(location).load()
        selector.contextItem = root
        val first = selector.evaluate().firstOrNull() ?: return ""
        return first.stringValue
    }

    private fun lastSlash(text: String): Int {
        var last = -1
        scan(text) { index, char, outside -> if (outside && char == '/') last = index }
        return last
    }

    private fun dropPredicates(step: String): String {
        val out = StringBuilder()
        scan(step) { _, char, outside -> if (outside && char != ']') out.append(char) }
        return out.toString()
    }

    /**
     * Calls [visit] for every character with whether it is outside every predicate, `{...}` literal and quoted
     * string. The opening and closing characters of a predicate or literal are reported as inside.
     */
    private fun scan(
        text: String,
        visit: (Int, Char, Boolean) -> Unit,
    ) {
        val state = ScanState()
        for ((index, char) in text.withIndex()) visit(index, char, state.outside(char))
    }

    /** The nesting of predicates, `{...}` literals and quotes while a location is read left to right. */
    private class ScanState {
        private var brackets = 0
        private var braces = 0
        private var quote: Char? = null

        private fun inside() = brackets > 0 || braces > 0 || quote != null

        /** Advances over [char]; true when it lies outside every predicate, literal and quote, before and after. */
        fun outside(char: Char): Boolean {
            val before = inside()
            advance(char)
            return !before && !inside()
        }

        private fun advance(char: Char) {
            val open = quote
            if (open != null) {
                if (char == open) quote = null
            } else if (brackets > 0 || braces > 0) {
                advanceInside(char)
            } else {
                advanceOutside(char)
            }
        }

        private fun advanceInside(char: Char) {
            when (char) {
                '\'', '"' -> quote = char
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces--
            }
        }

        private fun advanceOutside(char: Char) {
            when (char) {
                '[' -> brackets++
                '{' -> braces++
            }
        }
    }
}
