package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Consumer-side proof that the stored whole-tenant export package is a BagIt 1.0 directory of plain files
 * with SHA-256 manifests that `sha256sum -c` reads (RFC 8493; NIST SP 800-53 SI-7). The checks read the
 * produced files with the JDK only.
 */
class OffboardingExportReadableTest {
    private val tenant = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789"))
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:02:03Z"), ZoneOffset.UTC)
    private val request = ExportRequest(tenant, "off-1", "exp-1", ExportScope.TENANT, null, "ev-1")

    private fun item(
        cls: PackageContentClass,
        n: Int,
    ) = PackageItem(cls, "${cls.directory}-$n.txt", "$cls $n".toByteArray())

    private fun source(cls: PackageContentClass) =
        ExportClassSource {
            (1..2).map { n -> PackageCandidate(tenant, EntityRef("E1"), PeriodRef("2026-01"), item(cls, n)) }
        }

    private class FakeJobs : ExportJobs {
        var job: ExportJob? = null

        override fun find(
            tenant: TenantId,
            offboardingRef: String,
        ) = job

        override fun findLatest(
            tenant: TenantId,
            scope: ExportScope,
        ) = job

        override fun register(request: ExportRequest) =
            ExportJob(request.tenant, request.offboardingRef, request.exportRef, ExportState.REQUESTED)
                .also { job = it }

        override fun markReady(
            job: ExportJob,
            manifestRef: String,
            itemCount: Int,
            contents: List<String>,
        ) = job
            .copy(state = ExportState.READY, manifestRef = manifestRef, itemCount = itemCount, contents = contents)
            .also { this.job = it }

        override fun markFailed(
            job: ExportJob,
            reason: String,
        ) = job.copy(state = ExportState.FAILED, failureReason = reason).also { this.job = it }
    }

    private fun storedFiles(): PackageFiles {
        val stored = mutableListOf<PackageFiles>()
        val useCase =
            ExportTenantEvidence(
                ExportClassSources(PackageContentClass.entries.associateWith { source(it) }),
                FakeJobs(),
                { _, _, files ->
                    stored += files
                    "manifest-ref-1"
                },
                { true },
                { },
                clock,
            )
        useCase.execute(request).state shouldBe ExportState.READY
        stored.size shouldBe 1
        return stored[0]
    }

    private fun expectedDataPaths() =
        PackageContentClass.entries.flatMap { cls -> (1..2).map { "data/${cls.directory}/${item(cls, it).name}" } }

    private fun text(
        files: PackageFiles,
        path: String,
    ) = String(files.bytes(path)!!, Charsets.UTF_8)

    private fun sha256(bytes: ByteArray) =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val manifestLine = Regex("^([0-9a-f]{64}) {2}(.+)$")

    /** Paths whose bytes do not hash to the manifest value, or that are named but missing, or unlisted. */
    private fun mismatches(files: PackageFiles): Set<String> {
        val listed = mutableMapOf<String, String>()
        text(files, "manifest-sha256.txt").lines().filter { it.isNotEmpty() }.forEach { line ->
            val m = manifestLine.matchEntire(line) ?: error("malformed manifest line: $line")
            listed[m.groupValues[2]] = m.groupValues[1]
        }
        val data = files.paths.filter { it.startsWith("data/") }
        val bad = mutableSetOf<String>()
        for (p in (listed.keys + data)) {
            val b = files.bytes(p)
            if (b == null || listed[p] != sha256(b)) bad += p
        }
        return bad
    }

    @Test
    fun `INV-AC-26571 INV-AC-1905 every item of the five classes is stored under its directory with the same bytes`() {
        val files = storedFiles()
        files.paths.filter { it.startsWith("data/") }.toSet() shouldBe expectedDataPaths().toSet()
        PackageContentClass.entries.forEach { cls ->
            (1..2).forEach {
                val i = item(cls, it)
                files.bytes("data/${cls.directory}/${i.name}")!!.toList() shouldBe i.bytes().toList()
            }
        }
    }

    @Test
    fun `INV-AC-26571 README txt names the check command and bagit txt declares BagIt 1 0`() {
        val files = storedFiles()
        (text(files, "README.txt").lines().contains("  sha256sum -c manifest-sha256.txt")) shouldBe true
        text(files, "bagit.txt") shouldBe "BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n"
    }

    @Test
    fun `INV-AC-26571 each manifest line is the SHA-256 of the file it names`() {
        val files = storedFiles()
        val lines = text(files, "manifest-sha256.txt").lines().filter { it.isNotEmpty() }
        lines.forEach { (manifestLine.matches(it)) shouldBe true }
        lines.map { manifestLine.matchEntire(it)!!.groupValues[2] }.toSet() shouldBe
            files.paths.filter { it.startsWith("data/") }.toSet()
        mismatches(files) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-26571 one changed byte fails the manifest comparison`() {
        val files = storedFiles()
        val target = expectedDataPaths().first()
        val copy =
            files.paths.associateWith { files.bytes(it)!! }.toMutableMap().also {
                val b = it.getValue(target).copyOf()
                b[0] = (b[0].toInt() xor 1).toByte()
                it[target] = b
            }
        mismatches(PackageFiles(copy)) shouldBe setOf(target)
    }

    @Test
    fun `INV-AC-26571 bag-info txt states the offboarding scope and the item count of each class`() {
        val files = storedFiles()
        val info = text(files, "bag-info.txt").lines()
        info.contains("Package-Kind: OFFBOARDING") shouldBe true
        PackageContentClass.entries.forEach { info.contains("Item-Count-${it.directory}: 2") shouldBe true }
        info.single { it.startsWith("Payload-Oxum: ") }.endsWith(".12") shouldBe true
        text(files, "bag-info.txt") shouldContain "Tenant: ${tenant.value}"
    }
}
