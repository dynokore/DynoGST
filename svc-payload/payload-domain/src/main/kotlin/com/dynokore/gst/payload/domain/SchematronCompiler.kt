package com.dynokore.gst.payload.domain

/**
 * Compiles one Schematron source file into the stylesheet that a document run applies (INV-REQ-472, INV-REQ-776
 * behavior 1). The port names no XML or XSLT type; the adapter owns the mechanism.
 */
fun interface SchematronCompiler {
    /** Returns the stylesheet bytes of [schematron]; throws [SchematronCompileException] when it cannot be compiled. */
    fun compile(schematron: ByteArray): ByteArray
}

/** The source is not well-formed XML, asks for an external resource, or is not a Schematron schema (fail closed). */
class SchematronCompileException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
