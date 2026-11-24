package com.dynokore.gst.evidence.application.export

import com.dynokore.gst.evidence.application.integrity.IntegrityFailureRow
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStore
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedVerification
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * INV-AC-1815: the signature of the exported verification report verifies with the shipped key and fails once one
 * character of the report is edited. A real P-256 key pair and the real [BouncyCastleCms] (STK-13); the certificate is
 * built from plain JDK classes, so the test classpath gains the crypto implementation only.
 */
class ExportAuditPackSignatureTest {
    private val tenant = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789"))
    private val packId = UUID.fromString("00000000-0000-0000-0000-000000000042")
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:02:03Z"), ZoneOffset.UTC)
    private val cms = BouncyCastleCms()
    private val job =
        AuditPackExportJob(
            tenant,
            packId,
            "E1",
            "2026-01",
            AuditPackState.PENDING,
            Instant.parse("2026-11-15T01:00:00Z"),
            "user-7",
        )
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

    private class Pair(
        val key: JcaSigningKey,
        val certDer: ByteArray,
    )

    private fun newPair(): Pair {
        val pair =
            KeyPairGenerator
                .getInstance(
                    "EC",
                ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val der = selfSigned(pair)
        return Pair(JcaSigningKey(SignatureAlgorithm.ECDSA_P256_SHA256, pair.private, der), der)
    }

    private val signing = newPair()

    private fun signerOf(pair: Pair) =
        ReportSigner { report ->
            SignedReport(cms.sign(report, pair.key), pem(pair.certDer))
        }

    private class Rig(
        val useCase: ExportAuditPack,
        val stored: MutableList<PackageFiles>,
        val jobs: Jobs,
    )

    private class Jobs : AuditPackJobs {
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
        ) = job.copy(state = AuditPackState.READY, downloadUri = downloadUri, contents = contents)

        override fun markFailed(
            job: AuditPackExportJob,
            reason: String,
        ) = job.copy(state = AuditPackState.FAILED, failureReason = reason)
    }

    private fun rig(signer: ReportSigner): Rig {
        val stored = mutableListOf<PackageFiles>()
        val sources =
            PackageContentClass.entries.associateWith { cls ->
                AuditPackClassSource { _, _, _ ->
                    listOf(
                        PackageCandidate(
                            tenant,
                            EntityRef("E1"),
                            PeriodRef("2026-01"),
                            PackageItem(cls, "${cls.directory}-1.txt", "$cls".toByteArray()),
                        ),
                    )
                }
            }
        val jobs = Jobs()
        val store =
            ExportPackageStore { _, _, files ->
                stored += files
                "manifest-ref-1"
            }
        val integrity =
            object : IntegrityStore {
                override fun lock(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) = Unit

                override fun failureExists(
                    tenant: TenantId,
                    row: IntegrityFailureRow,
                ) = false

                override fun insertFailure(
                    tenant: TenantId,
                    row: IntegrityFailureRow,
                ) = Unit

                override fun insertRun(
                    tenant: TenantId,
                    row: IntegrityRunRow,
                ) = Unit

                override fun latestRun(
                    tenant: TenantId,
                    entityRef: String,
                    period: String,
                ) = run
            }
        return Rig(
            ExportAuditPack(AuditPackClassSources(sources), jobs, store, integrity, { true }, clock, signer),
            stored,
            jobs,
        )
    }

    private fun exported(signer: ReportSigner = signerOf(signing)): PackageFiles {
        val r = rig(signer)
        r.useCase.execute(job).state shouldBe AuditPackState.READY
        return r.stored.single()
    }

    private fun certificateOf(files: PackageFiles): ByteArray =
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(files.bytes("verification-key.pem")!!))
            .encoded

    private fun check(
        report: ByteArray,
        files: PackageFiles,
        trusted: ByteArray,
    ) = cms.verify(
        report,
        files.bytes("verification-report.p7s")!!,
        SignatureAlgorithm.ECDSA_P256_SHA256,
        listOf(trusted),
    )

    @Test
    fun `INV-AC-1815 the exported report signature verifies against the stored verification key`() {
        val files = exported()
        val result = check(files.bytes("verification-report.txt")!!, files, certificateOf(files))
        result shouldBe DetachedVerification.Verified(0)
        certificateOf(files).contentEquals(signing.certDer) shouldBe true
    }

    @Test
    fun `INV-AC-1815 one edited character of the report makes the same signature check fail`() {
        val files = exported()
        val report = files.bytes("verification-report.txt")!!
        val at = String(report).indexOf("VERIFIED") + "VERIFIE".length
        val edited = report.copyOf().also { it[at] = 'd'.code.toByte() }
        String(edited).contains("VERIFIEd") shouldBe true
        check(edited, files, certificateOf(files)).shouldBeInstanceOf<DetachedVerification.SignatureInvalid>()
    }

    @Test
    fun `INV-AC-1815 the original verification key of another key pair is an untrusted signer`() {
        val files = exported()
        val other = newPair()
        check(files.bytes("verification-report.txt")!!, files, other.certDer) shouldBe
            DetachedVerification.UntrustedSigner
    }

    @Test
    fun `INV-AC-1815 the unmodified report verifies again so the failure came from the edit`() {
        val files = exported()
        val report = files.bytes("verification-report.txt")!!
        val edited = report.copyOf().also { it[0] = (it[0] + 1).toByte() }
        check(edited, files, certificateOf(files)).shouldBeInstanceOf<DetachedVerification.SignatureInvalid>()
        check(report, files, certificateOf(files)) shouldBe DetachedVerification.Verified(0)
    }

    @Test
    fun `INV-AC-1815 the package lists both tag files, the verifier accepts it and a throwing signer stores nothing`() {
        val files = exported()
        val manifest = String(files.bytes("tagmanifest-sha256.txt")!!)
        manifest.contains("verification-report.p7s") shouldBe true
        manifest.contains("verification-key.pem") shouldBe true
        PackageVerifier
            .verify(
                object : PackageSource {
                    override fun paths() = files.paths.toSet()

                    override fun read(path: String) = files.bytes(path)!!
                },
            ).shouldBeInstanceOf<PackageVerification.Valid>()

        val r = rig { throw IllegalStateException("secret key bytes") }
        val failed = r.useCase.execute(job)
        failed.state shouldBe AuditPackState.FAILED
        failed.failureReason shouldBe "java.lang.IllegalStateException"
        r.stored shouldBe emptyList()
    }

    private fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE-----\n"

    // minimal X.509 v3 self-signed certificate (ecdsa-with-SHA256) from DER built by hand: JDK only
    private fun selfSigned(pair: KeyPair): ByteArray {
        val sigAlg = tlv(0x30, tlv(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
        val name =
            tlv(
                0x30,
                tlv(
                    0x31,
                    tlv(
                        0x30,
                        tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)) + tlv(0x0C, "test-signer".toByteArray()),
                    ),
                ),
            )
        val validity =
            tlv(0x30, tlv(0x17, "260101000000Z".toByteArray()) + tlv(0x17, "460101000000Z".toByteArray()))
        val tbs =
            tlv(
                0x30,
                tlv(0xA0, tlv(0x02, byteArrayOf(2))) + tlv(0x02, byteArrayOf(0x01, 0x02, 0x03)) + sigAlg + name +
                    validity +
                    name + pair.public.encoded,
            )
        val sig =
            Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }.run {
                update(tbs)
                sign()
            }
        return tlv(0x30, tbs + sigAlg + tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun tlv(
        tag: Int,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            body.size < 0x80 -> {
                out.write(body.size)
            }

            body.size < 0x100 -> {
                out.write(0x81)
                out.write(body.size)
            }

            else -> {
                out.write(0x82)
                out.write(body.size shr 8)
                out.write(body.size and 0xFF)
            }
        }
        out.write(body)
        return out.toByteArray()
    }
}
