package com.dynokore.gst.evidence.application.export

import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.AssemblyResult
import com.dynokore.platform.evidencestore.exportpackage.AuditPackAssembler
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageScope
import com.dynokore.platform.evidencestore.exportpackage.PackageSource
import com.dynokore.platform.evidencestore.exportpackage.PackageVerification
import com.dynokore.platform.evidencestore.exportpackage.PackageVerifier
import java.nio.charset.StandardCharsets
import java.time.Clock

/**
 * Builds, verifies and stores the audit pack of one entity and period with its verification report (INV-REQ-988).
 * It runs in the caller's tenant transaction. Fail closed (OWASP ASVS V1): any problem is a FAILED job with a
 * reason and nothing stored. A job that is not PENDING is returned unchanged. The actor is the job's `requestedBy`
 * user ID; no role is checked here.
 */
@Suppress("LongParameterList") // the use case, its ports and the verifier test seam (ruled 2026-11-22)
class ExportAuditPack(
    private val sources: AuditPackClassSources,
    private val jobs: AuditPackJobs,
    private val store: ExportPackageStore,
    private val integrity: IntegrityStore,
    private val chainIntegrity: ExportChainIntegrity,
    private val clock: Clock,
    private val signer: ReportSigner,
    private val verifier: (PackageFiles) -> PackageVerification = ::verifyAuditPack,
) {
    fun execute(job: AuditPackExportJob): AuditPackExportJob {
        if (job.state != AuditPackState.PENDING) return job
        return try {
            val stored = run(job)
            jobs.markReady(job, stored, CONTENTS)
        } catch (stop: Stop) {
            jobs.markFailed(job, stop.reason.take(MAX_REASON))
        }
    }

    private fun run(job: AuditPackExportJob): String {
        refuseUnbound()
        if (!port { chainIntegrity.allowsExport(job.tenant) }) {
            throw Stop("chain integrity does not allow the export (DEP-007)")
        }
        val candidates = candidates(job)
        val latest = port { integrity.latestRun(job.tenant, job.entityRef, job.periodRef) }
        val report = report(latest)
        // the exact bytes the writer stores as verification-report.txt: the text with one final LF, UTF-8
        val stored = (if (report.endsWith("\n")) report else report + "\n").toByteArray(StandardCharsets.UTF_8)
        val signed = port { signer.sign(stored) }
        val assembled = assemble(job, candidates, report, signed)
        refuseInvalid(assembled.files)
        return port { store.store(job.tenant, job.packId.toString(), assembled.files) }
    }

    private fun refuseUnbound() {
        val unbound = PackageContentClass.entries.filter { sources.source(it) == null }
        if (unbound.isNotEmpty()) throw Stop("no source for ${unbound.joinToString(", ") { it.name }}")
    }

    private fun assemble(
        job: AuditPackExportJob,
        candidates: List<PackageCandidate>,
        report: String,
        signed: SignedReport,
    ): AssemblyResult.Assembled =
        when (
            val result =
                AuditPackAssembler.assemble(
                    PackageScope.EntityPeriod(job.tenant, EntityRef(job.entityRef), PeriodRef(job.periodRef)),
                    candidates,
                    clock.instant(),
                    report,
                    signed.signatureDer,
                    signed.certificatePem,
                )
        ) {
            is AssemblyResult.Refused -> throw Stop("assembly refused: ${result.reason} ${result.itemName}")
            is AssemblyResult.Assembled -> result
        }

    private fun refuseInvalid(files: PackageFiles) {
        val verification = verifier(files)
        if (verification is PackageVerification.Invalid) {
            val codes = verification.problems.take(PROBLEMS_SHOWN).joinToString(", ") { it.code.name }
            throw Stop("package invalid: $codes")
        }
    }

    private fun candidates(job: AuditPackExportJob): List<PackageCandidate> =
        PackageContentClass.entries.flatMap { contentClass ->
            val source = sources.source(contentClass) ?: throw Stop("no source for ${contentClass.name}")
            port { source.candidates(job.tenant, job.entityRef, job.periodRef) }
        }

    private fun report(run: IntegrityRunRow?): String =
        if (run == null) {
            "result: NEVER_RUN\nlastRunAt: none\nchainLength: none\nperiodDigest: none\n"
        } else {
            "result: ${run.result.name}\nlastRunAt: ${run.runAt}\nchainLength: ${run.chainLength}\n" +
                "periodDigest: ${run.periodDigest ?: "none"}\n"
        }

    private class Stop(
        val reason: String,
    ) : RuntimeException(reason)

    /** Calls a port; any exception stops the export with the exception class name only. */
    private fun <T> port(block: () -> T): T = guarded(block).getOrElse { throw Stop(it.javaClass.name) }

    private companion object {
        const val MAX_REASON = 200
        const val PROBLEMS_SHOWN = 3

        /** The contract names of the six library classes, then the verification report. */
        val CONTENTS: List<String> = PackageContentClass.entries.map(::contractName) + "VERIFICATION_REPORT"

        fun contractName(contentClass: PackageContentClass): String =
            when (contentClass) {
                PackageContentClass.TRANSMITTED_XML -> "SUBMITTED_XML"
                PackageContentClass.ACKNOWLEDGEMENTS -> "ACKNOWLEDGEMENTS"
                PackageContentClass.DECISION_TRACES -> "DECISION_TRACES"
                PackageContentClass.RECONCILIATION_WORK_PAPERS -> "RECONCILIATION_WORK_PAPER"
                PackageContentClass.SOURCE_DOCUMENT_REFERENCES -> "SOURCE_REFERENCES"
                PackageContentClass.AUDIT_LOG -> "AUDIT_LOG"
            }
    }
}

/** The production verification: [PackageVerifier] over a view of the package files. */
fun verifyAuditPack(files: PackageFiles): PackageVerification =
    PackageVerifier.verify(
        object : PackageSource {
            override fun paths(): Set<String> = files.paths.toSet()

            override fun read(path: String): ByteArray = files.bytes(path) ?: error("missing $path")
        },
    )

@Suppress("TooGenericExceptionCaught") // a failing port is a FAILED job, never a rethrow (fail closed)
private fun <T> guarded(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }
