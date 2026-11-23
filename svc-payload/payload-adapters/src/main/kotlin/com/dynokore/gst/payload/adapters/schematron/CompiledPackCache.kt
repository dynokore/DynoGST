package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.FailedAssertion
import com.dynokore.gst.payload.domain.SchematronCompiler
import com.dynokore.gst.payload.domain.SchematronFile
import com.dynokore.gst.payload.domain.SchematronValidator
import com.dynokore.gst.payload.domain.StagedPackMissingException
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.gst.payload.domain.ValidationOutcome
import com.dynokore.platform.integrationcanon.schematron.CompiledSchematron
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds one [CompiledSchematron] per Schematron file of a pack version in memory (INV-REQ-472 behaviors 2 and 3,
 * INV-REQ-1359, INV-REQ-1210, INV-REQ-776). A pack is a list of files with no entry file, so the set is held in the
 * ascending byte order of the UTF-8 file names and a document run returns the failed asserts of every stylesheet,
 * concatenated in that order. Each file is compiled once at [activate], or once on the first miss of [validate]
 * from [source]; never per document. No version is evicted. Nothing here reaches the `rules` schema.
 */
class CompiledPackCache(
    private val runner: SchematronRunner,
    private val source: StagedPackSource,
    private val compiler: SchematronCompiler,
) : SchematronValidator {
    private val held = ConcurrentHashMap<String, List<CompiledSchematron>>()

    /** Compiles every file of [files] and holds the set as [packVersion], replacing a held set of the same version. */
    fun activate(
        packVersion: String,
        files: List<SchematronFile>,
    ) {
        held[packVersion] = compileAll(files)
    }

    override fun validate(
        packVersion: String,
        document: ByteArray,
    ): ValidationOutcome {
        val set = held[packVersion] ?: miss(packVersion)
        return ValidationOutcome(
            set.flatMap { compiled ->
                compiled.validate(document).failedAsserts.map {
                    FailedAssertion(it.id, it.location, it.test, it.role, it.text)
                }
            },
        )
    }

    // computeIfAbsent runs the loader at most once per key; a throw from it holds nothing.
    private fun miss(packVersion: String): List<CompiledSchematron> =
        held.computeIfAbsent(packVersion) {
            compileAll(source.fetch(packVersion) ?: throw StagedPackMissingException(packVersion))
        }

    private fun compileAll(files: List<SchematronFile>): List<CompiledSchematron> {
        require(files.isNotEmpty()) { "a Schematron pack holds at least one file" }
        return files
            .sortedWith(BY_UTF8_NAME)
            .map { runner.compile(compiler.compile(it.content)) }
    }

    private companion object {
        /** Byte order of the UTF-8 name, never locale collation. */
        val BY_UTF8_NAME =
            Comparator<SchematronFile> { a, b ->
                Arrays.compareUnsigned(a.name.toByteArray(Charsets.UTF_8), b.name.toByteArray(Charsets.UTF_8))
            }
    }
}
