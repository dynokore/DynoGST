package com.dynokore.gst.evidence.application.export

/**
 * Signs the verification report of an audit pack (INV-REQ-663). [report] is the exact byte string the package writer
 * stores as `verification-report.txt`. The implementation holds the key by reference and reads it per use (STK-29).
 */
fun interface ReportSigner {
    fun sign(report: ByteArray): SignedReport
}

/** [signatureDer] is the detached CMS `SignedData` (STK-13); [certificatePem] is the verification certificate. */
class SignedReport(
    val signatureDer: ByteArray,
    val certificatePem: String,
)
