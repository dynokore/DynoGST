package com.dynokore.gst.evidence.application.export

import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.evidencestore.exportpackage.PackageSource
import com.dynokore.platform.evidencestore.exportpackage.PackageVerification
import com.dynokore.platform.evidencestore.exportpackage.PackageVerifier
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExportAuditPackTest {
    private val t1 = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789"))
    private val t2 = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456790"))
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:02:03Z"), ZoneOffset.UTC)
    private val packId = UUID.fromString("00000000-0000-0000-0000-000000000042")
    private val pending =
        AuditPackExportJob(t1, packId, "E1", "2026-01", AuditPackState.PENDING, Instant.parse("2026-11-15T01:00:00Z"), "user-7")
    private val run =
        IntegrityRunRow(
            "E1",
            "2026-01",
            "run-9",
            Instant.parse("2026-11-14T10:00:00Z"),
            RunResult.VERIFIED,
            RunCause.entries.first(),
            12,
            "ab".repeat(32),
        )

    private fun candidate(
        cls: PackageContentClass,
        name: String,
        tenant: TenantId = t1,
        entity: String = "E1",
        period: String = "2026-01",
    ) = PackageCandidate(
        tenant,
        EntityRef(entity),
        PeriodRef(period),
        PackageItem(cls, name, "$cls $name".toByteArray()),
    )

    private fun sources(
        extra: (PackageContentClass) -> List<PackageCandidate> = { emptyList() },
    ): Map<PackageContentClass, AuditPackClassSource?> =
        PackageContentClass.entries.associateWith { cls ->
            AuditPackClassSource { _, _, _ -> listOf(candidate(cls, "${cls.directory}-1.txt")) + extra(cls) }
        }

    private class FakeJobs : AuditPackJobs {
        var changes = 0

        override fun register(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
            requestedBy: String,
        ): AuditPackExportJob = error("not used")

        override fun find(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
            packId: UUID,
        ): AuditPackExportJob? = null

        override fun markReady(
            job: AuditPackExportJob,
            downloadUri: String,
            contents: List<String>,
        ) = job.copy(state = AuditPackState.READY, downloadUri = downloadUri, contents = contents).also { changes++ }

        override fun markFailed(
            job: AuditPackExportJob,
            reason: String,
        ) = job.copy(state = AuditPackState.FAILED, failureReason = reason).also { changes++ }
    }

    private class Rig(
        sources: Map<PackageContentClass, AuditPackClassSource?>,
        latest: IntegrityRunRow?,
        allows: Boolean = true,
        storeFails: Boolean = false,
        clock: Clock,
    ) {
        val jobs = FakeJobs()
        val stored = mutableListOf<PackageFiles>()
        val storedRefs = mutableListOf<String>()
        val useCase =
            ExportAuditPack(
                AuditPackClassSources(sources),
                jobs,
                { _, ref, files ->
                    if (storeFails) throw IllegalStateException("disk full secret")
                    stored += files
                    storedRefs += ref
                    "manifest-ref-1"
                },
                object : IntegrityStore {
                    override fun lock(
                        tenant: TenantId,
                        entityRef: String,
                        period: String,
                    ) = Unit

                    override fun failureExists(
                        tenant: TenantId,
                        row: com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow,
                    ) = false

                    override fun insertFailure(
                        tenant: TenantId,
                        row: com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow,
                    ) = Unit

                    override fun insertRun(
                        tenant: TenantId,
                        row: IntegrityRunRow,
                    ) = Unit

                    override fun latestRun(
                        tenant: TenantId,
                        entityRef: String,
                        period: String,
                    ) = latest
                },
                { allows },
                clock,
            )
    }

    private fun rig(
        sources: Map<PackageContentClass, AuditPackClassSource?> = sources(),
        latest: IntegrityRunRow? = run,
        storeFails: Boolean = false,
    ) = Rig(sources, latest, true, storeFails, clock)

    private fun PackageFiles.text(path: String) = String(bytes(path)!!)

    @Test
    fun `INV-AC-25916 the stored package opens with the verifier and holds the report with the four fields of the latest run`() {
        val r = rig()
        val job = r.useCase.execute(pending)
        job.state shouldBe AuditPackState.READY
        job.downloadUri shouldBe "manifest-ref-1"
        r.storedRefs shouldBe listOf(packId.toString())
        r.stored.size shouldBe 1
        val files = r.stored[0]
        (PackageVerifier.verify(
            object : PackageSource {
                override fun paths() = files.paths.toSet()

                override fun read(path: String) = files.bytes(path)!!
            },
        ) is PackageVerification.Valid) shouldBe true
        files.text("verification-report.txt") shouldBe
            "result: VERIFIED\nlastRunAt: 2026-11-14T10:00:00Z\nchainLength: 12\nperiodDigest: ${"ab".repeat(32)}\n"
        files.text("tagmanifest-sha256.txt").contains("verification-report.txt") shouldBe true
    }

    @Test
    fun `INV-AC-25916 a period never verified states NEVER_RUN and is still exported`() {
        val r = rig(latest = null)
        val job = r.useCase.execute(pending)
        job.state shouldBe AuditPackState.READY
        r.stored[0].text("verification-report.txt") shouldBe
            "result: NEVER_RUN\nlastRunAt: none\nchainLength: none\nperiodDigest: none\n"
    }

    @Test
    fun `INV-AC-25916 items of another entity or period are left out`() {
        val r =
            rig(
                sources {
                    listOf(
                        candidate(it, "${it.directory}-other-entity.txt", entity = "E2"),
                        candidate(it, "${it.directory}-other-period.txt", period = "2026-02"),
                    )
                },
            )
        r.useCase.execute(pending).state shouldBe AuditPackState.READY
        val paths = r.stored[0].paths.filter { it.startsWith("data/") }
        paths.size shouldBe 6
        paths.any { it.contains("other") } shouldBe false
    }

    @Test
    fun `INV-AC-25916 an item of another tenant fails the job and stores nothing`() {
        val r = rig(sources { listOf(candidate(it, "${it.directory}-foreign.txt", tenant = t2)) })
        val job = r.useCase.execute(pending)
        job.state shouldBe AuditPackState.FAILED
        job.failureReason!!.startsWith("assembly refused: FOREIGN_TENANT") shouldBe true
        r.stored shouldBe emptyList()
        job.downloadUri shouldBe null
    }

    @Test
    fun `INV-AC-25916 a class without a source fails the job naming the class`() {
        val r = rig(sources() - PackageContentClass.AUDIT_LOG)
        val job = r.useCase.execute(pending)
        job.state shouldBe AuditPackState.FAILED
        job.failureReason shouldBe "no source for AUDIT_LOG"
        r.stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25916 a store failure fails the job with the exception class name only`() {
        val r = rig(storeFails = true)
        val job = r.useCase.execute(pending)
        job.state shouldBe AuditPackState.FAILED
        job.failureReason shouldBe "java.lang.IllegalStateException"
        r.stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25916 a READY or FAILED job is returned unchanged and nothing is stored`() {
        listOf(AuditPackState.READY, AuditPackState.FAILED).forEach { state ->
            val r = rig()
            val done = pending.copy(state = state)
            r.useCase.execute(done) shouldBe done
            r.stored shouldBe emptyList()
            r.jobs.changes shouldBe 0
        }
    }

    @Test
    fun `INV-AC-25916 the contents list the six contract names then VERIFICATION_REPORT`() {
        rig().useCase.execute(pending).contents shouldBe
            listOf(
                "SUBMITTED_XML",
                "ACKNOWLEDGEMENTS",
                "DECISION_TRACES",
                "RECONCILIATION_WORK_PAPER",
                "SOURCE_REFERENCES",
                "AUDIT_LOG",
                "VERIFICATION_REPORT",
            )
    }
}
