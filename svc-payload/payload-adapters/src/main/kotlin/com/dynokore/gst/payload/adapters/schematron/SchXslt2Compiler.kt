package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.SchematronCompileException
import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.platform.integrationcanon.xml.HardenedXml
import net.sf.saxon.lib.Feature
import net.sf.saxon.lib.ResourceResolver
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.SaxonApiException
import net.sf.saxon.s9api.Serializer
import net.sf.saxon.s9api.XsltExecutable
import net.sf.saxon.trans.XPathException
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource

/**
 * Compiles Schematron to an SVRL-reporting XSLT with the SchXslt2 transpiler (include, expand and compile-for-svrl are
 * the phases of its one stylesheet), run by Saxon in this process (INV-REQ-776 behavior 1). Fail closed: the source is
 * read with the hardened SAX parser of [HardenedXml] (no document type declaration, so no external entity), and the
 * Saxon resource resolver, unparsed-text resolver and collection finder refuse every URI, so an `sch:include` of an
 * external file is refused and nothing is fetched (OWASP XXE prevention). The output is deterministic.
 */
class SchXslt2Compiler : SchematronCompiler {
    private val processor: Processor =
        Processor(false).also {
            val config = it.underlyingConfiguration
            config.setBooleanProperty(Feature.ALLOW_EXTERNAL_FUNCTIONS, false)
            config.setBooleanProperty(Feature.XINCLUDE, false)
            config.setResourceResolver(
                ResourceResolver { request ->
                    throw XPathException("external resource refused: ${request.uri}")
                },
            )
            config.setUnparsedTextURIResolver { uri, _, _ -> throw XPathException("external text refused: $uri") }
            config.setCollectionFinder { _, uri -> throw XPathException("collection refused: $uri") }
        }

    private val transpiler: XsltExecutable by lazy {
        val stream =
            checkNotNull(SchXslt2Compiler::class.java.classLoader.getResourceAsStream(TRANSPILER)) {
                "$TRANSPILER is not on the class path"
            }
        stream.use { processor.newXsltCompiler().compile(StreamSource(it, "schxslt2:transpile.xsl")) }
    }

    override fun compile(schematron: ByteArray): ByteArray {
        val bytes =
            try {
                transpile(schematron)
            } catch (e: SaxonApiException) {
                refuse(e)
            } catch (e: SAXException) {
                refuse(e)
            } catch (e: ParserConfigurationException) {
                refuse(e)
            }
        return bytes.takeIf { it.isNotEmpty() }
            ?: throw SchematronCompileException("the source is not a Schematron schema")
    }

    private fun refuse(cause: Exception): Nothing =
        throw SchematronCompileException("Schematron source refused: ${cause.message}", cause)

    private fun transpile(schematron: ByteArray): ByteArray {
        val reader =
            HardenedXml
                .saxParserFactory()
                .also { it.isNamespaceAware = true }
                .newSAXParser()
                .xmlReader
        val source = SAXSource(reader, InputSource(schematron.inputStream()))
        val out = ByteArrayOutputStream()
        val serializer = processor.newSerializer(out)
        serializer.setOutputProperty(Serializer.Property.METHOD, "xml")
        serializer.setOutputProperty(Serializer.Property.ENCODING, "UTF-8")
        serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes")
        transpiler.load30().transform(source, serializer)
        return out.toByteArray()
    }

    private companion object {
        const val TRANSPILER = "content/transpile.xsl"
    }
}
